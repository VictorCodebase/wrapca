package com.victorkithinji.wrap.wrapca.api;

import com.victorkithinji.wrap.wrapca.facade.WrapSessionFacade;
import com.victorkithinji.wrap.wrapca.history.RunRecord;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * HTTP layer for run history endpoints.
 * Returns raw RunRecord objects — no separate DTO wrapping required as
 * RunRecord is already Jackson-serialisable via @Value + JavaTimeModule.
 */
@RestController
@RequestMapping("/api/runs")
@RequiredArgsConstructor
public class RunHistoryController {

	private final WrapSessionFacade facade;

	/**
	 * GET /api/runs
	 * Returns all completed run records sorted most-recent first.
	 * Returns an empty array when no runs exist.
	 */
	@GetMapping
	public ResponseEntity<List<RunRecord>> getAllRuns() {
		return ResponseEntity.ok(facade.getAllRuns());
	}

	/**
	 * GET /api/runs/{runId}
	 * Returns the full run record for a single run, including the parameters
	 * snapshot and result file path.
	 * Returns 404 when the runId does not match any persisted record.
	 */
	@GetMapping("/{runId}")
	public ResponseEntity<RunRecord> getRunById(@PathVariable String runId) {
		RunRecord record = facade.getRunById(runId);
		if (record == null) {
			return ResponseEntity.notFound().build();
		}
		return ResponseEntity.ok(record);
	}
}