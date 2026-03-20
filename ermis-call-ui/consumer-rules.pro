    # Giữ lại các lớp và interface của JNA mà thư viện của bạn cần
    -keep class com.sun.jna.** { *; }
    -keep public interface com.sun.jna.** { *; }

    # Giữ lại các file tài nguyên native mà JNA sử dụng để load thư viện .so
    -keepresourcefiles "com/sun/jna/**"