cmake_minimum_required(VERSION 3.22.1)
project(mobile_agent_native C ASM)

# Mobile Agent ships a PRoot userspace for both 64-bit and 32-bit ARM.
# Everything that differs between the two is captured here; the rest of this
# file is ABI agnostic.
#
#   MH_LOADER_ADDRESS  matches LOADER_ADDRESS in third_party/proot/src/arch.h.
#                      PRoot maps its loader at this fixed address, so it must
#                      be inside the tracee's address space: a 64-bit constant
#                      is unmappable in a 32-bit process.
#   MH_POKEDATA_WORKAROUND
#                      HAS_POKEDATA_WORKAROUND in arch.h is defined for ARM64
#                      only. The workaround stub (pokedata_workaround) lives in
#                      loader/assembly.S behind `#if defined(__aarch64__)`, and
#                      loader-info.c exists purely to record its offset, so
#                      neither may be referenced on 32-bit ARM.
#   MH_PAGE_ALIGNMENT  Android's 16 KB page-size requirement applies to 64-bit
#                      libraries. ARMv7 kernels are 4 KB only; padding to 16 KB
#                      just inflates the APK.
if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(MH_LOADER_ADDRESS "0x2000000000")
    set(MH_POKEDATA_WORKAROUND ON)
    set(MH_PAGE_ALIGNMENT 16384)
elseif(ANDROID_ABI STREQUAL "armeabi-v7a")
    set(MH_LOADER_ADDRESS "0x20000000")
    set(MH_POKEDATA_WORKAROUND OFF)
    set(MH_PAGE_ALIGNMENT 4096)
else()
    message(FATAL_ERROR
        "Mobile Agent supports arm64-v8a and armeabi-v7a; got '${ANDROID_ABI}'")
endif()

set(PROJECT_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../..")
set(PROOT_ROOT "${PROJECT_ROOT}/third_party/proot/src")
set(SHM_ROOT "${PROJECT_ROOT}/third_party/libandroid-shmem")
set(TALLOC_ROOT "${PROJECT_ROOT}/third_party/talloc")

add_library(pocketspawn SHARED pocket_spawn.c)

add_library(talloc SHARED "${TALLOC_ROOT}/talloc.c")
target_include_directories(talloc PUBLIC "${TALLOC_ROOT}")
target_compile_definitions(talloc PRIVATE
    _GNU_SOURCE
    __STDC_WANT_LIB_EXT1__=1
    TALLOC_BUILD_VERSION_MAJOR=2
    TALLOC_BUILD_VERSION_MINOR=4
    TALLOC_BUILD_VERSION_RELEASE=3
)
set_target_properties(talloc PROPERTIES OUTPUT_NAME talloc SOVERSION 2)

add_library(android-shmem SHARED "${SHM_ROOT}/shmem.c")
target_include_directories(android-shmem PUBLIC "${SHM_ROOT}")
target_compile_definitions(android-shmem PRIVATE _PATH_TMP="/data/data/com.anonamus.mobileagent/cache/")
target_link_libraries(android-shmem PRIVATE log android)

set(PROOT_SOURCES
    cli/cli.c cli/proot.c cli/note.c
    execve/enter.c execve/exit.c execve/shebang.c execve/elf.c
    execve/ldso.c execve/auxv.c execve/aoxp.c
    path/binding.c path/glue.c path/canon.c path/f2fs-bug.c
    path/path.c path/proc.c path/temp.c
    syscall/seccomp.c syscall/syscall.c syscall/chain.c syscall/enter.c
    syscall/exit.c syscall/sysnum.c syscall/socket.c syscall/heap.c
    syscall/rlimit.c syscall/pipe_shadow.c
    tracee/tracee.c tracee/mem.c tracee/reg.c tracee/event.c
    tracee/seccomp.c tracee/statx.c
    ptrace/ptrace.c ptrace/user.c ptrace/wait.c
    extension/extension.c extension/ashmem_memfd/ashmem_memfd.c
    extension/kompat/kompat.c
    extension/fake_id0/chown.c extension/fake_id0/chroot.c
    extension/fake_id0/getsockopt.c extension/fake_id0/sendmsg.c
    extension/fake_id0/socket.c extension/fake_id0/open.c
    extension/fake_id0/unlink.c extension/fake_id0/rename.c
    extension/fake_id0/chmod.c extension/fake_id0/utimensat.c
    extension/fake_id0/access.c extension/fake_id0/exec.c
    extension/fake_id0/link.c extension/fake_id0/symlink.c
    extension/fake_id0/mk.c extension/fake_id0/stat.c
    extension/fake_id0/helper_functions.c extension/fake_id0/fake_id0.c
    extension/hidden_files/hidden_files.c extension/mountinfo/mountinfo.c
    extension/port_switch/port_switch.c extension/sysvipc/sysvipc.c
    extension/sysvipc/sysvipc_msg.c extension/sysvipc/sysvipc_sem.c
    extension/sysvipc/sysvipc_shm.c extension/link2symlink/link2symlink.c
    extension/fix_symlink_size/fix_symlink_size.c
)
list(TRANSFORM PROOT_SOURCES PREPEND "${PROOT_ROOT}/")

file(WRITE "${CMAKE_CURRENT_BINARY_DIR}/build.h" [=[
#ifndef BUILD_H
#define BUILD_H
#define VERSION "5.1.107.91"
#define HAVE_PROCESS_VM 1
#define HAVE_SECCOMP_FILTER 1
#endif
]=])

add_executable(prootloader
    "${PROOT_ROOT}/loader/loader.c"
    "${PROOT_ROOT}/loader/assembly.S"
)
target_include_directories(prootloader PRIVATE "${PROOT_ROOT}")
target_compile_options(prootloader PRIVATE -fPIC -ffreestanding)
target_link_options(prootloader PRIVATE
    -static -nostdlib
    "-Wl,--build-id=none,--rosegment,-Ttext=${MH_LOADER_ADDRESS},-z,noexecstack"
)
if(MH_POKEDATA_WORKAROUND)
    target_link_options(prootloader PRIVATE "-Wl,-u,pokedata_workaround")
endif()
set_target_properties(prootloader PROPERTIES OUTPUT_NAME "prootloader-bin" SUFFIX "")

# Only ARM64 defines HAS_POKEDATA_WORKAROUND, so only ARM64 needs the generated
# offset table. On 32-bit ARM the loader has no such symbol and PRoot never
# references it.
if(MH_POKEDATA_WORKAROUND)
    add_custom_command(
        OUTPUT "${CMAKE_CURRENT_BINARY_DIR}/loader-info.c"
        COMMAND "${CMAKE_COMMAND}"
            "-DREADELF=${CMAKE_READELF}"
            "-DLOADER=$<TARGET_FILE:prootloader>"
            "-DOUTPUT=${CMAKE_CURRENT_BINARY_DIR}/loader-info.c"
            -P "${CMAKE_CURRENT_SOURCE_DIR}/GenerateLoaderInfo.cmake"
        DEPENDS prootloader "${CMAKE_CURRENT_SOURCE_DIR}/GenerateLoaderInfo.cmake"
        VERBATIM
    )
    set(PROOT_GENERATED_SOURCES "${CMAKE_CURRENT_BINARY_DIR}/loader-info.c")
else()
    set(PROOT_GENERATED_SOURCES "")
    add_custom_target(prootloader-ready DEPENDS prootloader)
endif()

add_executable(proot ${PROOT_SOURCES} ${PROOT_GENERATED_SOURCES})
if(NOT MH_POKEDATA_WORKAROUND)
    add_dependencies(proot prootloader-ready)
endif()
target_include_directories(proot PRIVATE
    "${PROOT_ROOT}"
    "${CMAKE_CURRENT_BINARY_DIR}"
    "${TALLOC_ROOT}"
    "${SHM_ROOT}"
)
target_compile_definitions(proot PRIVATE
    _FILE_OFFSET_BITS=64
    _GNU_SOURCE
    ARG_MAX=131072
    WITH_LIBANDROID_SHMEM
    PROOT_UNBUNDLE_LOADER="."
)
target_compile_options(proot PRIVATE
    -Wall -Wextra -O2
    "SHELL:-include string.h"
    "SHELL:-include ${SHM_ROOT}/shm.h"
)
target_link_options(proot PRIVATE "-Wl,-z,noexecstack")
target_link_libraries(proot PRIVATE talloc android-shmem)
set_target_properties(proot PROPERTIES OUTPUT_NAME "proot-bin" SUFFIX "")

# AGP packages shared-library targets but not executable targets. Each carrier
# advertises the APK entry, then is replaced by the real executable after link.
add_library(proot-carrier SHARED executable_carrier.c)
set_target_properties(proot-carrier PROPERTIES OUTPUT_NAME proot)
add_dependencies(proot-carrier proot)
add_custom_command(TARGET proot-carrier POST_BUILD
    COMMAND "${CMAKE_COMMAND}" -E copy "$<TARGET_FILE:proot>" "$<TARGET_FILE:proot-carrier>"
)

add_library(prootloader-carrier SHARED executable_carrier.c)
set_target_properties(prootloader-carrier PROPERTIES OUTPUT_NAME prootloader)
add_dependencies(prootloader-carrier prootloader)
add_custom_command(TARGET prootloader-carrier POST_BUILD
    COMMAND "${CMAKE_COMMAND}" -E copy "$<TARGET_FILE:prootloader>" "$<TARGET_FILE:prootloader-carrier>"
)

foreach(target pocketspawn talloc android-shmem proot proot-carrier prootloader-carrier)
    target_link_options(${target} PRIVATE "-Wl,-z,max-page-size=${MH_PAGE_ALIGNMENT}")
endforeach()
