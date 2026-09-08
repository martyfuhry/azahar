add_executable(citra_bench
    citra_bench.cpp
    emu_window_null.cpp
    emu_window_null.h
)

set_target_properties(citra_bench PROPERTIES OUTPUT_NAME "azahar-bench")

create_target_directory_groups(citra_bench)

if (BSD STREQUAL "NetBSD")
    include(DisablePaxMprotect)
    disable_pax_mprotect(citra_bench)
endif()

target_link_libraries(citra_bench PRIVATE citra_common citra_core video_core audio_core)
target_link_libraries(citra_bench PRIVATE ${PLATFORM_LIBRARIES} Threads::Threads)
