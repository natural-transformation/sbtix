object Three {
  def doStuff(): Unit = {
    println("Hello from three")
  }
}

object Main extends App {
  One.doStuff()
  Two.doStuff()
  Three.doStuff()
}
