/* Link-time stand-in for Cupcake's liblog.so; never shipped. */
int __android_log_print(int prio, const char* tag, const char* fmt, ...) { return 0; }
