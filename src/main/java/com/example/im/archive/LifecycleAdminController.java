package com.example.im.archive;

import com.example.im.common.ApiBusinessException;
import com.example.im.config.ImProperties;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.UUID;

import static com.example.im.archive.ArchiveDtos.*;

@RestController
@RequestMapping("/internal/v1/lifecycle")
final class LifecycleAdminController {
    private final MessageArchiveService archive;
    private final LifecycleService lifecycle;
    private final byte[] secret;

    LifecycleAdminController(MessageArchiveService archive, LifecycleService lifecycle, ImProperties properties) {
        this.archive = archive;
        this.lifecycle = lifecycle;
        this.secret = properties.archive().adminSecret().getBytes(StandardCharsets.UTF_8);
    }

    @PostMapping("/archives")
    @ResponseStatus(HttpStatus.CREATED)
    ArchiveBatchView archive(@RequestHeader(value="X-IM-Lifecycle-Secret",required=false) String provided,
                             @Valid @RequestBody ArchiveRequest request) {
        authenticate(provided);
        return archive.archive(request);
    }

    @GetMapping("/archives/{tableGroup}/{batchId}")
    ArchiveBatchView archive(@RequestHeader(value="X-IM-Lifecycle-Secret",required=false) String provided,
                             @PathVariable int tableGroup, @PathVariable UUID batchId) {
        authenticate(provided);
        return archive.get(tableGroup, batchId);
    }

    @PostMapping("/cleanup")
    CleanupView cleanup(@RequestHeader(value="X-IM-Lifecycle-Secret",required=false) String provided) {
        authenticate(provided);
        return lifecycle.cleanup();
    }

    @PostMapping("/partitions/ensure")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void partitions(@RequestHeader(value="X-IM-Lifecycle-Secret",required=false) String provided) {
        authenticate(provided);
        lifecycle.ensurePartitions();
    }

    @GetMapping("/status")
    Map<String,Object> status(@RequestHeader(value="X-IM-Lifecycle-Secret",required=false) String provided) {
        authenticate(provided);
        return lifecycle.status();
    }

    private void authenticate(String provided) {
        if (provided == null || !MessageDigest.isEqual(secret, provided.getBytes(StandardCharsets.UTF_8))) {
            throw new ApiBusinessException("LIFECYCLE_ADMIN_UNAUTHORIZED", "Lifecycle admin secret is invalid", 401);
        }
    }
}
