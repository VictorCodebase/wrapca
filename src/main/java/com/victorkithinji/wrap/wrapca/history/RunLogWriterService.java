package com.victorkithinji.wrap.wrapca.history;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Persists a {@link RunRecord} to a JSON file under {@code {wrap.data.root}/runs/}.
 *
 * <p>Filename pattern: {@code {startedAt-yyyyMMdd_HHmmss}_{PHASE}.json}
 * e.g. {@code 20250115_143022_PRE_FIRE.json}</p>
 *
 * <p>Creates the runs directory on first write. Does not throw on failure —
 * a write error is logged as a warning so the simulation response is still returned.</p>
 */
@Slf4j
@Service
public class RunLogWriterService {

	private static final DateTimeFormatter FILENAME_TS =
		DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss").withZone(ZoneOffset.UTC);

	private final Path runsDir;
	private final ObjectMapper mapper;

	public RunLogWriterService(@Value("${wrap.data.root}") String dataRoot) {
		this.runsDir = Paths.get(dataRoot, "runs");
		this.mapper = new ObjectMapper()
			.registerModule(new JavaTimeModule())
			.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
			.enable(SerializationFeature.INDENT_OUTPUT);
	}

	/**
	 * Serialises {@code record} to a JSON file in the runs directory.
	 * Returns the path of the written file, or {@code null} if the write failed.
	 *
	 * @param record the completed run record to persist
	 * @return the path of the written file, or {@code null} on failure
	 */
	public Path write(RunRecord record) {
		try {
			Files.createDirectories(runsDir);
			String filename = FILENAME_TS.format(record.getStartedAt())
				+ "_" + record.getPhase().name() + ".json";
			Path target = runsDir.resolve(filename);
			mapper.writeValue(target.toFile(), record);
			log.info("Run record written: {}", target);
			return target;
		} catch (IOException e) {
			log.warn("Failed to write run record for runId={}: {}", record.getRunId(), e.getMessage());
			return null;
		}
	}
}