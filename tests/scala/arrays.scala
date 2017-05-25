package tests.scala

import tests.scala.TestClasses._

class ArrayStagingArea extends StandardTest {
  "A 2d array" should "print the correct results" in {
    val code = """
      void main() {
        int x[2][2];
        int i, j = 0;
        int count = 0;
        
        for (i = 0; i < 2; i++) {
          for (j = 0; j < 2; j++) {
            x[i][j] = count;
            count += 1;
          }
        }
        
        for (i = 0; i < 2; i++) {
          for (j = 0; j < 2; j++) {
            printf("%d\n", x[i][j]);
          }
        }  
      }"""

    checkResults(code)
  }
}

class ArrayInitTest extends StandardTest {
  "Sized arrays initialized with initLists" should "print the correct results" in {
    val code = """
      void main() {
        int padding; // lets test an offset
        
        int x[5] = {1, 2, 3, 4, 5};
        printf("%d %d %d %d %d\n", x[0], x[1], x[2], x[3], x[4]);
        
        char y[5] = {'a', 'b', 'c', 'd', 'e'};
        printf("%c %c %c %c %c\n", y[0], y[1], y[2], y[3], y[4]);
        
        double z[5] = {5.6, 38.5, 2.945, 347.2, 378.2};
        printf("%f %f %f %f %f\n", z[0], z[1], z[2], z[3], z[4]);
      }"""

    checkResults(code)
  }
  
  "array indexed with a division binary expression" should "print the correct results" in {
    val code = """
      void main() {
        int x[5] = {1, 2, 3, 4, 5};
        int y = 4;
        int z = 2;
        printf("%d\n", x[2]);
        printf("%d\n", x[4 / 2]);
        printf("%d\n", x[y / 2]);
        printf("%d\n", x[4 / z]);
      }"""

    checkResults(code)
  }
  
  "Unsized arrays initialized with initLists" should "print the correct results" in {
    val code = """
      void main() {
        int x[] = {1, 2, 3, 4, 5};
        printf("%d %d %d %d %d\n", x[0], x[1], x[2], x[3], x[4]);
        
        char y[] = {'a', 'b', 'c', 'd', 'e'};
        printf("%c %c %c %c %c\n", y[0], y[1], y[2], y[3], y[4]);
        
        double z[] = {5.6, 38.5, 2.945, 347.2, 378.2};
        printf("%f %f %f %f %f\n", z[0], z[1], z[2], z[3], z[4]);
      }"""

    checkResults(code)
  }
}

class SimpleHigherDimArrays extends StandardTest {
  "check for array clobbering" should "print the correct results" in {
    val code = """
      void main() {
        int x[3][9];
        x[1][0] = 43424;
        x[1][1] = 43;
        x[1][2] = 64565;
        x[0][0] = 5645;
        x[0][1] = 878;
        x[0][2] = 98797;
        printf("%d %d %d\n", x[1][0], x[1][1], x[1][2]);
        printf("%d %d %d\n", x[0][0], x[0][1], x[0][2]);
      }"""

    checkResults(code)
  }
}

class HigherDimArrays extends StandardTest {
   
  "A rectangular 2d array" should "print the correct results" in {
    val code = """
      void main() {
        int x[3][9];
        int i, j = 0;
        int count = 0;
        
        for (i = 0; i < 3; i++) {
          for (j = 0; j < 9; j++) {
            x[i][j] = count;
            count += 1;
          }
        }
        
        for (i = 0; i < 3; i++) {
          for (j = 0; j < 9; j++) {
            printf("%d\n", x[i][j]);
          }
        }  
      }"""

    checkResults(code)
  }
  
  "A 3d array" should "print the correct results" in {
    val code = """
      void main() {
        int x[2][2][2];
        int i, j, k = 0;
        int count = 0;
        
        for (i = 0; i < 2; i++) {
          for (j = 0; j < 2; j++) {
            for (k = 0; k < 2; k++) {
              x[i][j][k] = count;
              count += 1;
            }
          }
        }
        
        for (i = 0; i < 2; i++) {
          for (j = 0; j < 2; j++) {
            for (k = 0; k < 2; k++) {
              printf("%d\n", x[i][j][k]);
            }
          }
        }  
      }"""

    checkResults(code)
  }
}

class ArrayTest extends StandardTest {

  "Array sanity check" should "print the correct results" in {
    val code = """
      void main() {
        char s[100] = "hello";
        if(s == &s[0]) printf("true. ");
        if(s == &s) printf("true.");
      }"""

    checkResults(code)
  }

  "A trivial array assignment" should "print the correct results" in {
    val code = """
      void main() {
        int x[5];
        x[2] = 5;
        printf("%d\n", x[2]);
      }"""

    checkResults(code)
  }
  
  "An array with dimensions from a binary expr" should "print the correct results" in {
    val code = """
      void main() {
        int x[5*5];
        x[23] = 5;
        printf("%d\n", x[23]);
      }"""

    checkResults(code)
  }
  
  "assignment operators on an array element" should "print the correct results" in {
    val code = """
      void main() {
        int x[5] = {1, 2, 3, 4, 5};
        x[0] += 1;
        x[1] -= 1;
        x[2] *= 1;
        x[3] ^= 1;
        printf("%d %d %d %d\n", x[0], x[1], x[2], x[3]);
      }"""

    checkResults(code)
  }
  
  "A trivial array binary expression" should "print the correct results" in {
    val code = """
      void main() {
        int x[5];
        x[2] = 5;
        x[3] = 3;
        printf("%d\n", x[2] * x[3]);
      }"""

    checkResults(code)
  }
  
  "An array subscript with advanced binary expression" should "print the correct results" in {
    val code = """
      void main() {
        int x[5];
        int y = 2;
        x[1] = 3;
        x[3] = 12;
        printf("%d\n", x[y - 2 + x[1]]);
      }"""

    checkResults(code)
  }
  
  "An array prefixed subscript" should "print the correct results" in {
    val code = """
      void main() {
        int x[5] = {3, 68, 44, 29, 45};
        int y = 0;
        printf("%d %d\n", x[++y], y);
      }"""

    checkResults(code)
  }
  
  "An array postfixed subscript" should "print the correct results" in {
    val code = """
      void main() {
        int x[5] = {3, 68, 44, 29, 45};
        int y = 0;
        printf("%d %d\n", x[y++], y);
      }"""

    checkResults(code)
  }
  
 
}
