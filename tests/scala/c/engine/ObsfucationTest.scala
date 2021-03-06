package scala.c.engine

class ObsfucationTest extends StandardTest {
  "obsfucation 1" should "print the correct results" in {
    val code = """

      #include <stdio.h>
 main() {
   long long P = 1,
             E = 2,
             T = 5,
             A = 61,
             L = 251,
             N = 3659,
             R = 271173410,
             G = 1479296389,
             //x[] = { G * R * E * E * T , P * L * A * N * E * T };
             x[] = { G * R * E * E * T , P * L * A * N * E * T };
     printf("%s\n", (char*)x);
 }"""

    checkResults(code)
  }



  "obsfucation 2" should "print the correct results" in {
    val code = """
   #define u unsigned char
   #define v while(*x)
   #define z(x) putchar(*x);
   #define y(x) ++*x;
   #define p(x) ++x;
   #define e(x) --*x;
   #define d(x) --x;
   #define w(x) x x
   main(){u *x=calloc(12,1);u *l=x;w(w(w(y(x))))w(y(x))v{p(x)w(w(y(x)))w(y(x))y(x)p
   (x)w(w(w(y(x))))w(y(x))p(x)w(y(x))y(x)p(x)w(w(w(y(x))))y(x)w(w(d(x)))e(x)}p(x)w(
   y(x))z(x)p(x)y(x)z(x)w(w(y(x)))w(y(x))y(x)w(z(x))w(y(x))y(x)z(x)p(x)w(y(x))z(x)p
   (x)w(e(x))e(x)z(x)w(d(x))z(x)w(y(x))y(x)z(x)w(w(e(x)))w(e(x))z(x)w(w(w(e(x))))z(
   x)p(x)y(x)z(x)free(l);}
   """

    checkResults(code)
  }

  "obsfucation 3" should "print the correct results" in {
    val code = """
        #include <stdio.h>
        #include <stdint.h>

        struct s
        {
           int64_t i[9];
        } S;
        #define N 11

        int main()
        {
            int s=sizeof(S),i=s,j=N;
            int x[N]={i,i+=29,i+=7,i,i+=3,2*N+10,s+=15,i,i+=3,i-=6,i-=8};
            while(j--)
            {
                printf("%d\n", x[j]);
                //putchar(x[N-1-j]);
            }
            return 0;
        }
   """

    checkResults(code)
  }
}