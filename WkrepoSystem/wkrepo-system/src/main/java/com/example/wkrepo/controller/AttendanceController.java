@PostMapping("/default-attendance-settings")
public String saveDefaultSettings(@ModelAttribute DefaultAttendanceSettings settings, Model model) {
    // 保存默认设置逻辑
    defaultAttendanceService.save(settings);
    return "redirect:/attendance/default-settings";
}

@GetMapping("/default-attendance-settings")
public String getDefaultSettings(Model model) {
    DefaultAttendanceSettings settings = defaultAttendanceService.getDefaultSettings();
    model.addAttribute("settings", settings);
    return "attendance/default-settings";
}