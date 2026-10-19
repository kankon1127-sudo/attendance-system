@Service
public class DefaultAttendanceService {
    @Autowired
    private DefaultAttendanceRepository repository;

    public DefaultAttendanceSettings getDefaultSettings() {
        return repository.findById(1L).orElse(new DefaultAttendanceSettings());
    }

    public void save(DefaultAttendanceSettings settings) {
        repository.save(settings);
    }
}