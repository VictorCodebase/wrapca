package com.victorkithinji.wrap.wrapca.history;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Reads all persisted {@link RunRecord} JSON files from {@code {wrap.data.root}/runs/}
 * and returns them sorted by {@code startedAt} descending (most recent first).
 *
 * <p>Files that cannot be deserialised are logged as warnings and skipped —
 * a single corrupt file does not prevent the rest from loading.</p>
 *
 * <p>Returns an empty list if the runs directory does not exist or contains no
 * {@code .json} files.</p>
 */
@Slf4j
@Service
public class RunLogReaderService {

	private final Path runsDir;
	private final ObjectMapper mapper;

	public RunLogReaderService(@Value("${wrap.data.root}") String dataRoot) {
		this.runsDir = Paths.get(dataRoot, "runs");
		this.mapper = new ObjectMapper()
			.registerModule(new JavaTimeModule());
	}

	/**
	 * Returns all run records found in the runs directory, sorted most-recent first.
	 *
	 * @return immutable list of run records; empty if none exist or directory is absent
	 */
	public List<RunRecord> readAll() {
		if (!Files.isDirectory(runsDir)) {
			log.debug("Runs directory does not exist yet: {}", runsDir);
			return Collections.emptyList();
		}

		try (Stream<Path> files = Files.list(runsDir)) {
			List<RunRecord> records = files
				.filter(p -> p.getFileName().toString().endsWith(".json"))
				.map(this::deserialise)
				.filter(r -> r != null)
				.sorted(Comparator.comparing(RunRecord::getStartedAt).reversed())
				.toList();
			log.debug("Loaded {} run record(s) from {}", records.size(), runsDir);
			return records;
		} catch (IOException e) {
			log.warn("Failed to list runs directory {}: {}", runsDir, e.getMessage());
			return Collections.emptyList();
		}
	}

	/**
	 * Returns the single run record matching {@code runId}, or {@code null} if not found.
	 *
	 * @param runId the run identifier to search for
	 * @return the matching record, or {@code null}
	 */
	public RunRecord findById(String runId) {
		return readAll().stream()
			.filter(r -> runId.equals(r.getRunId()))
			.findFirst()
			.orElse(null);
	}

	// -------------------------------------------------------------------------

	private RunRecord deserialise(Path file) {
		try {
			return mapper.readValue(file.toFile(), RunRecord.class);
		} catch (IOException e) {
			log.warn("Skipping unreadable run record {}: {}", file.getFileName(), e.getMessage());
			return null;
		}
	}
}