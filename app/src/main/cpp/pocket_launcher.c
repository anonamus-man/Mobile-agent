#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <sys/prctl.h>
#include <unistd.h>

int main(int argc, char **argv) {
    if (argc < 2) {
        fputs("Mobile Agent launcher requires a program\n", stderr);
        return 64;
    }
    if (prctl(PR_SET_DUMPABLE, 1, 0, 0, 0) != 0) {
        perror("Mobile Agent launcher could not enable child tracing");
        return 70;
    }
    unsetenv("LD_LIBRARY_PATH");
    unsetenv("LD_PRELOAD");
    execv(argv[1], &argv[1]);
    perror("Mobile Agent launcher exec failed");
    return errno == 0 ? 71 : errno;
}
