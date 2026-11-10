// Eclipse: Dolphin's Android build opens content:// URIs through Java here. Eclipse always passes real file paths.
#pragma once
#include <string>
#include <string_view>
inline bool IsPathAndroidContent(std::string_view) { return false; }
inline int OpenAndroidContent(std::string_view, std::string_view) { return -1; }
inline bool DeleteAndroidContent(std::string_view) { return false; }
inline long long GetAndroidContentSizeAndIsDirectory(std::string_view) { return -1; }
inline std::string GetAndroidContentDisplayName(std::string_view) { return {}; }
#include <ios>
inline std::string OpenModeToAndroid(std::string_view mode) { return std::string(mode); }
inline std::string OpenModeToAndroid(std::ios_base::openmode) { return "r"; }
