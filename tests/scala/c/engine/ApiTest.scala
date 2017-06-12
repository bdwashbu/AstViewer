package c.engine

import Interpreter._

class ApiTest extends StandardTest {
  "interp test one" should "print the correct results" in {
    
    implicit val state = new State
    
    c"""int i = 1432;"""
    c"""printf("%d\n", i);"""
    c"""
      float x = 2.5f;
      printf("what: %.1f\n", x);
      
      """
      assert(getResults(state.stdout.toList) == (Seq("1432", "what: 2.5")))
  }

  "func interpolator" should "print the correct results" in {
    import Interpreter._
    implicit val state = new State

    func"""
      int add(int x, int y) {
          return x + y;
      }"""

    c"""
       printf("%d\n", add(4,5));
     """

    assert(getResults(state.stdout.toList).mkString == "9")

    state.stdout.clear

    func"""
      int mult(int x, int y) {
          return x * y;
      }"""

    c"""
       printf("%d\n", mult(add(1,2), add(5,4)));
     """

    assert(getResults(state.stdout.toList).mkString == "27")
  }

  "func interpolator 2" should "print the correct results" in {
    import Interpreter._
    implicit val state = new State

    c"""
      float blah = 4.34;
      """

    c"""
      printf("%.2f\n", blah);
     """

    assert(getResults(state.stdout.toList).mkString == "4.34")
  }
}