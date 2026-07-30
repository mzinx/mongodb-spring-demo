package com.mzinx.demo.web;

import java.util.Arrays;
import java.util.List;

import org.springframework.context.ApplicationContext;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.mzinx.demo.web.dto.StreamConfigRequest;
import com.mzinx.mongodb.changestream.ChangeStreamManager;
import com.mzinx.mongodb.changestream.listener.ChangeStreamListener;
import com.mzinx.mongodb.changestream.model.ChangeStreamConfig;
import com.mzinx.mongodb.changestream.model.ChangeStreamStatus;
import com.mzinx.mongodb.changestream.service.ChangeStreamConfigService;

/**
 * Change stream lifecycle API.
 * <p>
 * Create/config/start/stop map onto {@link ChangeStreamConfigService} saves and
 * deletes; the library's {@link ChangeStreamManager} reconciles the running
 * streams on its next refresh cycle ({@code change-stream.config-refresh-interval}).
 * Runtime status is read from {@link ChangeStreamManager}.
 */
@RestController
@RequestMapping("/api/streams")
public class StreamController {

    private final ChangeStreamConfigService changeStreamConfigService;
    private final ChangeStreamManager changeStreamManager;
    private final ApplicationContext context;

    StreamController(ChangeStreamConfigService changeStreamConfigService, ChangeStreamManager changeStreamManager,
            ApplicationContext context) {
        this.changeStreamConfigService = changeStreamConfigService;
        this.changeStreamManager = changeStreamManager;
        this.context = context;
    }

    /** All persisted change stream definitions. */
    @GetMapping
    public List<ChangeStreamConfig> list() {
        return changeStreamConfigService.findAll();
    }

    /** Runtime status of every change stream registered on this instance. */
    @GetMapping("/status")
    public List<ChangeStreamStatus> status() {
        return changeStreamManager.getChangeStreams();
    }

    @GetMapping("/{id}/status")
    public ResponseEntity<ChangeStreamStatus> status(@PathVariable String id) {
        return ResponseEntity.of(changeStreamManager.getChangeStreamStatus(id));
    }

    /** Names of all {@link ChangeStreamListener} beans available as targets. */
    @GetMapping("/listeners")
    public List<String> listeners() {
        return Arrays.asList(context.getBeanNamesForType(ChangeStreamListener.class));
    }

    /** Creates or reconfigures a change stream. */
    @PostMapping
    public ChangeStreamConfig save(@RequestBody StreamConfigRequest request) {
        return changeStreamConfigService.save(request.toConfig());
    }

    /** Starts (enables) a change stream. */
    @PostMapping("/{id}/start")
    public ResponseEntity<ChangeStreamConfig> start(@PathVariable String id) {
        return setEnabled(id, true);
    }

    /** Stops (disables) a change stream without deleting its definition. */
    @PostMapping("/{id}/stop")
    public ResponseEntity<ChangeStreamConfig> stop(@PathVariable String id) {
        return setEnabled(id, false);
    }

    /** Deletes a change stream definition; the running stream is stopped. */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        changeStreamConfigService.delete(id);
        return ResponseEntity.noContent().build();
    }

    private ResponseEntity<ChangeStreamConfig> setEnabled(String id, boolean enabled) {
        ChangeStreamConfig config = changeStreamConfigService.findById(id);
        if (config == null)
            return ResponseEntity.notFound().build();
        config.setEnabled(enabled);
        return ResponseEntity.ok(changeStreamConfigService.save(config));
    }
}
