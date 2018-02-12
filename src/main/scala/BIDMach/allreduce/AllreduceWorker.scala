package BIDMach.allreduce


import BIDMach.allreduce.AllreduceNode.{DataSink, DataSource}
import BIDMach.allreduce.buffer.{ReducedDataBuffer, ScatteredDataBuffer}
import akka.actor.{Actor, ActorRef, Terminated}


class AllreduceWorker(config: WorkerConfig,
                      dataSource: DataSource,
                      dataSink: DataSink) extends Actor with akka.actor.ActorLogging {

  val thReduce = config.threshold.thReduce
  val thComplete = config.threshold.thComplete

  val dataSize = config.metaData.dataSize
  val maxChunkSize = config.metaData.maxChunkSize

  val workerDiscoveryTimeout = config.discoveryTimeout

  var currentConfig: RoundConfig = RoundConfig(-1, -1, self, new Map[Int, ActorRef](), -1)
  var isCurrentRoundCompleted = true

  // Data
  var data: Array[Float] = new Array(dataSize)
  var dataRange: Array[Int] = Array.empty
  var maxBlockSize = 0
  var minBlockSize = 0
  var myBlockSize = 0

  // Buffer
  var scatterBlockBuf: ScatteredDataBuffer = ScatteredDataBuffer.empty // store scattered data received
  var reduceBlockBuf: ReducedDataBuffer = ReducedDataBuffer.empty // store reduced data received

  // Output
  var output: Array[Float] = new Array(dataSize)
  var outputCount: Array[Int] = new Array(dataSize)

  println(s"\n----Worker ${self.path}")
  println(s"\n----Worker ${self.path}: Thresholds: thReduce = ${thReduce}, thComplete = ${thComplete}");

  def receive = {

    case s: StartAllreduce => {
      try {
        handleRoundConfig(s.config)
        isCurrentRoundCompleted = false
        fetch()
        scatter()
      } catch {
        case e: Throwable => printStackTrace("start all reduce", e);
      }
    }

    case s: ScatterBlock => {
      try {
        log.debug(s"\n----Worker ${self.path}: receive scattered data from round ${s.config.round} srcId = ${s.srcId}, destId = ${s.destId}, chunkId=${s.chunkId}")
        handleRoundConfig(s.config)
        handleScatterBlock(s);
      } catch {
        case e: Throwable => printStackTrace("scatter block", e);
      }
    }

    case r: ReduceBlock => {
      try {
        log.debug(s"\n----Worker ${self.path}: Receive reduced data from round ${r.config.round}, srcId = ${r.srcId}, destId = ${r.destId}, chunkId=${r.chunkId}")
        handleRoundConfig(r.config)
        handleReduceBlock(r);
      } catch {
        case e: Throwable => printStackTrace("reduce block", e);
      }
    }

    case Terminated(a) =>
      for ((idx, worker) <- currentConfig.peers) {
        if (worker == a) {
          currentConfig.peers -= idx
        }
      }
  }

  private def handleRoundConfig(config : RoundConfig): Unit = {
    if (config < currentConfig) { // outdated msg, discard
      return
    }
    if (config > currentConfig && !isCurrentRoundCompleted) { // falling behind, catch up 
      catchUp()
    }
    handleBuffer(config)
    currentConfig = config
  }

  private def catchUp() {
    log.warning(s"\n----Worker ${self.path}: Force completing round ${currentConfig.round}")
    val unreducedChunkIds = scatterBlockBuf.getUnreducedChunkIds()
    for (i <- unreducedChunkIds) {
      reduceAndBroadcast(i)
    }
    completeRound()
  }

  private def handleBuffer(config : RoundConfig) {
    // re-initialize buffers when grid size (and thus block size) changes
    var numPeers = currentConfig.peers.size
    if (config.peers.size != numPeers) {
      currentConfig.peers = config.peers
      numPeers = config.peers.size

      // prepare meta-data
      dataRange = initDataBlockRanges()
      myBlockSize = blockSize(currentConfig.workerId)
      maxBlockSize = blockSize(0)
      minBlockSize = blockSize(numPeers - 1)

      // reusing old implementation of buffer defaulting max lag to 1, since this is per-round worker
      scatterBlockBuf = ScatteredDataBuffer(
        dataSize = myBlockSize,
        peerSize = numPeers,
        reducingThreshold = thReduce,
        maxChunkSize = maxChunkSize
      )

      reduceBlockBuf = ReducedDataBuffer(
        maxBlockSize = maxBlockSize,
        minBlockSize = minBlockSize,
        totalDataSize = dataSize,
        peerSize = numPeers,
        completionThreshold = thComplete,
        maxChunkSize = maxChunkSize
      )
    } else {
      // update peer addresses in case of member changes
      if (config.workerId != currentConfig.workerId || currentConfig.peers != config.peers) {
        currentConfig.peers = config.peers
      }

      // prepare buffer for new round  
      scatterBlockBuf.prepareNewRound()
      reduceBlockBuf.prepareNewRound()
    }
  }

  private def handleReduceBlock(r: ReduceBlock) = {
    if (r.value.size > maxChunkSize) {
      throw new RuntimeException(s"Reduced block of size ${r.value.size} is larger than expected.. Max msg size is $maxChunkSize")
    } else if (r.destId != currentConfig.workerId) {
      throw new RuntimeException(s"Message with destination ${r.destId} was incorrectly routed to node $currentConfig.workerId")
    } else if (r.config.round > currentConfig.round) {
      throw new RuntimeException(s"New round ${r.config.round} should have been prepared, but current round is $currentConfig.round")
    }

    if (r.config.round < currentConfig.round) {
      log.debug(s"\n----Worker ${self.path}: Outdated reduced data")
    } else {
      reduceBlockBuf.store(r.value, r.srcId, r.chunkId, r.count)
      if (reduceBlockBuf.reachCompletionThreshold()) {
        log.debug(s"\n----Worker ${self.path}: Receive enough reduced data (numPeers = ${currentConfig.peers.size}) for round ${r.config.round}, complete")
        completeRound()
      }
    }
  }

  private def handleScatterBlock(s: ScatterBlock) = {

    if (s.destId != currentConfig.workerId) {
      throw new RuntimeException(s"Scatter block should be directed to $currentConfig.workerId, but received ${s.destId}")
    } else if (s.config.round > currentConfig.round) {
      throw new RuntimeException(s"New round ${s.config.round} should have been prepared, but current round is $currentConfig.round")
    }

    if (s.config.round < currentConfig.round) {
      log.debug(s"\n----Worker ${self.path}: Outdated scattered data")
    } else {
      scatterBlockBuf.store(s.value, s.srcId, s.chunkId)
      if (scatterBlockBuf.reachReducingThreshold(s.chunkId)) {
        log.debug(s"\n----Worker ${self.path}: receive ${scatterBlockBuf.count(s.chunkId)} scattered data (numPeers = ${currentConfig.peers.size}), chunkId =${s.chunkId} for round ${s.config.round}, start reducing")
        reduceAndBroadcast(s.chunkId)
      }
    }

  }

  private def blockSize(id: Int) = {
    val (start, end) = range(id)
    end - start
  }

  private def initDataBlockRanges() = {
    val stepSize = math.ceil(dataSize * 1f / numPeers).toInt
    Array.range(0, dataSize, stepSize)
  }

  private def range(idx: Int): (Int, Int) = {
    if (idx >= numPeers - 1)
      (dataRange(idx), dataSize)
    else
      (dataRange(idx), dataRange(idx + 1))
  }

  private def fetch() = {
    log.debug(s"\nfetch ${currentConfig.round}")
    val input = dataSource(AllReduceInputRequest(currentConfig.round))
    if (dataSize != input.data.size) {
      throw new IllegalArgumentException(s"\nInput data size ${input.data.size} is different from initialization time $dataSize!")
    }
    data = input.data
  }

  private def flush() = {
    reduceBlockBuf.getWithCounts(output, outputCount)
    log.debug(s"\n----Worker ${self.path}: Flushing output at completed round $currentConfig.round")
    dataSink(AllReduceOutput(output, outputCount, currentConfig.round))
  }

  private def scatter() = {
    var numPeers = currentConfig.peers.size
    for (peerId <- 0 until numPeers) {
      val idx = (peerId + currentConfig.workerId) % numPeers
      val worker = currentConfig.peers(idx)
      //Partition the dataBlock if it is too big
      val (blockStart, blockEnd) = range(idx)
      val peerBlockSize = blockEnd - blockStart
      val peerNumChunks = math.ceil(1f * peerBlockSize / maxChunkSize).toInt
      for (i <- 0 until peerNumChunks) {
        val chunkStart = math.min(i * maxChunkSize, peerBlockSize - 1);
        val chunkEnd = math.min((i + 1) * maxChunkSize - 1, peerBlockSize - 1);
        val chunkSize = chunkEnd - chunkStart + 1
        val chunk: Array[Float] = new Array(chunkSize)

        System.arraycopy(data, blockStart + chunkStart, chunk, 0, chunkSize);
        log.debug(s"\n----Worker ${self.path}: send msg from ${currentConfig.workerId} to ${idx}, chunkId: ${i}")
        val scatterMsg = ScatterBlock(chunk, currentConfig.workerId, idx, i, currentConfig)
        if (worker == self) {
          handleScatterBlock(scatterMsg)
        } else {
          sendTo(worker, scatterMsg)
        }
      }
    }
  }

  private def reduceAndBroadcast(chunkId: Int) = {
    val (reducedData, reduceCount) = scatterBlockBuf.reduce(chunkId)
    broadcast(reducedData, chunkId, reduceCount)
  }

  private def broadcast(data: Array[Float], chunkId: Int, reduceCount: Int) = {
    log.debug(s"\n----Worker ${self.path}: Start broadcasting")
    var numPeers = currentConfig.peers.size
    for (i <- 0 until numPeers) {
      val peerworkerId = (i + currentConfig.workerId) % numPeers
      val worker = currentConfig.peers(peerworkerId)
      log.debug(s"\n----Worker ${self.path}: Broadcast reduced block src: ${currentConfig.workerId}, dest: ${peerworkerId}, chunkId: ${chunkId}, round: ${currentConfig.round}")
      val reduceMsg = ReduceBlock(data, currentConfig.workerId, peerworkerId, chunkId, currentConfig, reduceCount)
      if (worker == self) {
        handleReduceBlock(reduceMsg)
      } else {
        sendTo(worker, reduceMsg)
      }
    }
  }


  private def completeRound() = {
    flush()
    sendTo(currentConfig.lineMaster, CompleteAllreduce(currentConfig.workerId, currentConfig))
    isCurrentRoundCompleted = true
  }

  private def printStackTrace(location: String, e: Throwable): Unit = {
    import java.io.{PrintWriter, StringWriter}
    val sw = new StringWriter
    e.printStackTrace(new PrintWriter(sw))
    val stackTrace = sw.toString
    println(e, s"error in $location, $stackTrace")
  }

  protected def sendTo(recipient: ActorRef, msg: Any) = {
    recipient ! msg
  }

}

