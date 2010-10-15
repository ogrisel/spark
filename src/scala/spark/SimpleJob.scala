package spark

import java.util.{HashMap => JHashMap}

import scala.collection.mutable.HashMap
import scala.collection.mutable.Queue

import mesos._


/**
 * A simple implementation of Job that just runs each task in an array.
 */
class SimpleJob[T: ClassManifest](
  sched: MesosScheduler, tasks: Array[Task[T]], val jobId: Int)
extends Job with Logging
{
  // Maximum time to wait to run a task in a preferred location (in ms)
  val LOCALITY_WAIT = System.getProperty("spark.locality.wait", "3000").toLong

  // CPUs and memory to claim per task from Mesos
  val CPUS_PER_TASK = System.getProperty("spark.task.cpus", "1").toInt
  val MEM_PER_TASK = System.getProperty("spark.task.mem", "512").toInt

  val callingThread = currentThread
  val numTasks = tasks.length
  val results = new Array[T](numTasks)
  val launched = new Array[Boolean](numTasks)
  val finished = new Array[Boolean](numTasks)
  val tidToIndex = HashMap[Int, Int]()

  var allFinished = false
  val joinLock = new Object() // Used to wait for all tasks to finish

  var errorHappened = false
  var errorCode = 0
  var errorMessage = ""

  var tasksLaunched = 0
  var tasksFinished = 0

  var lastPreferredLaunchTime = System.currentTimeMillis

  // Queue of pending tasks for each node
  val pendingTasksForNode = new HashMap[String, Queue[Int]]

  // Queue containing all pending tasks
  val allPendingTasks = new Queue[Int]

  for (i <- 0 until numTasks) {
    addPendingTask(i)
  }

  def addPendingTask(index: Int) {
    allPendingTasks += index
    for (host <- tasks(index).preferredLocations) {
      pendingTasksForNode(host) += index
    }
  }

  def setAllFinished() {
    joinLock.synchronized {
      allFinished = true
      joinLock.notifyAll()
    }
  }

  def join() {
    joinLock.synchronized {
      while (!allFinished)
        joinLock.wait()
    }
  }

  def getPendingTasksForNode(host: String): Queue[Int] = {
    pendingTasksForNode.getOrElse(host, Queue())
  }

  // Dequeue a pending task from the given queue and return its index.
  // Return None if the queue is empty.
  def findTaskFromQueue(queue: Queue[Int]): Option[Int] = {
    while (!queue.isEmpty) {
      val index = queue.dequeue
      if (!launched(index) && !finished(index)) {
        return Some(index)
      }
    }
    return None
  }

  // Dequeue a pending task for a given node and return its index.
  // If localOnly is set to false, allow non-local tasks as well.
  def findTask(host: String, localOnly: Boolean): Option[Int] = {
    findTaskFromQueue(getPendingTasksForNode(host)) match {
      case Some(task) => Some(task)
      case None =>
        if (localOnly) None
        else findTaskFromQueue(allPendingTasks)
    }
  }

  def isPreferredLocation(task: Task[T], host: String): Boolean = {
    val locs = task.preferredLocations
    return (locs.contains(host) || locs.isEmpty)
  }

  def slaveOffer(offer: SlaveOffer, availableCpus: Int, availableMem: Int)
      : Option[TaskDescription] = {
    if (tasksLaunched < numTasks && availableCpus >= CPUS_PER_TASK &&
        availableMem >= MEM_PER_TASK) {
      val time = System.currentTimeMillis
      val localOnly = (time - lastPreferredLaunchTime < LOCALITY_WAIT)
      val host = offer.getHost
      findTask(host, localOnly) match {
        case Some(index) => {
          val task = tasks(index)
          val taskId = sched.newTaskId()
          // Figure out whether the task's location is preferred
          val preferred = isPreferredLocation(task, host)
          val prefStr = if(preferred) "preferred" else "non-preferred"
          val message =
            "Starting task %d:%d as TID %s on slave %s: %s (%s)".format(
              index, jobId, taskId, offer.getSlaveId, host, prefStr)
          logInfo(message)
          // Do various bookkeeping
          sched.taskIdToJobId(taskId) = jobId
          tidToIndex(taskId) = index
          task.markStarted(offer)
          launched(index) = true
          tasksLaunched += 1
          if (preferred)
            lastPreferredLaunchTime = time
          // Create and return the Mesos task object
          val params = new JHashMap[String, String]
          params.put("cpus", "" + CPUS_PER_TASK)
          params.put("mem", "" + MEM_PER_TASK)
          val serializedTask = Utils.serialize(task)
          logDebug("Serialized size: " + serializedTask.size)
          return Some(new TaskDescription(taskId, offer.getSlaveId,
            "task_" + taskId, params, serializedTask))
        }
        case _ =>
      }
    }
    return None
  }

  def statusUpdate(status: TaskStatus) {
    status.getState match {
      case TaskState.TASK_FINISHED =>
        taskFinished(status)
      case TaskState.TASK_LOST =>
        taskLost(status)
      case TaskState.TASK_FAILED =>
        taskLost(status)
      case TaskState.TASK_KILLED =>
        taskLost(status)
      case _ =>
    }
  }

  def taskFinished(status: TaskStatus) {
    val tid = status.getTaskId
    val index = tidToIndex(tid)
    if (!finished(index)) {
      tasksFinished += 1
      logInfo("Finished TID %d (progress: %d/%d)".format(
        tid, tasksFinished, numTasks))
      // Deserialize task result
      val result = Utils.deserialize[TaskResult[T]](status.getData)
      results(index) = result.value
      // Update accumulators
      Accumulators.add(callingThread, result.accumUpdates)
      // Mark finished and stop if we've finished all the tasks
      finished(index) = true
      // Remove TID -> jobId mapping from sched
      sched.taskIdToJobId.remove(tid)
      if (tasksFinished == numTasks)
        setAllFinished()
    } else {
      logInfo("Ignoring task-finished event for TID " + tid +
        " because task " + index + " is already finished")
    }
  }

  def taskLost(status: TaskStatus) {
    val tid = status.getTaskId
    val index = tidToIndex(tid)
    if (!finished(index)) {
      logInfo("Lost TID %d (task %d:%d)".format(tid, jobId, index))
      launched(index) = false
      sched.taskIdToJobId.remove(tid)
      tasksLaunched -= 1
      // Re-enqueue the task as pending
      addPendingTask(index)
    } else {
      logInfo("Ignoring task-lost event for TID " + tid +
        " because task " + index + " is already finished")
    }
  }

  def error(code: Int, message: String) {
    // Save the error message
    errorHappened = true
    errorCode = code
    errorMessage = message
    // Indicate to caller thread that we're done
    setAllFinished()
  }
}