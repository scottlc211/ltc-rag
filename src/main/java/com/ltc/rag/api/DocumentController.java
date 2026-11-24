package com.ltc.rag.api;

import com.ltc.rag.auth.AuthContext;
import com.ltc.rag.common.ApiException;
import com.ltc.rag.domain.DocumentView;
import com.ltc.rag.service.IngestionService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

/**
 * 知识库管理接口（写操作仅 ADMIN）。
 *
 * <p>docKey 是幂等键：同一个 docKey 重复上传 = 版本更新（旧向量会被清理），
 * 不传则退化成用文件名，调用方应当显式传，否则改个文件名就变成两篇文档。
 */
@RestController
@RequestMapping("/v1/documents")
public class DocumentController {

    private final IngestionService ingestion;

    public DocumentController(IngestionService ingestion) {
        this.ingestion = ingestion;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Object> upload(@RequestParam("file") MultipartFile file,
                                         @RequestParam(required = false) String namespace,
                                         @RequestParam(required = false) String docKey,
                                         @RequestParam(required = false) String title,
                                         @RequestParam(required = false) String url,
                                         @RequestParam(defaultValue = "false") boolean async) {
        AuthContext.requireAdmin();
        if (file.isEmpty()) {
            throw ApiException.badRequest("EMPTY_FILE", "上传文件为空");
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw ApiException.badRequest("READ_FILE_FAILED", "读取上传文件失败：" + e.getMessage());
        }
        String fileName = file.getOriginalFilename() == null ? "upload.bin" : file.getOriginalFilename();
        if (async) {
            IngestionService.IngestJobView job = ingestion.submitAsync(namespace, docKey, title, url, fileName, bytes);
            return ResponseEntity.accepted().body(java.util.Map.of(
                    "jobId", job.jobId(),
                    "status", job.status(),
                    "pollUrl", "/v1/documents/jobs/" + job.jobId()));
        }
        IngestionService.IngestResult result = ingestion.ingest(namespace, docKey, title, url, fileName, bytes);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new ApiDtos.IngestResponse(result.document(), result.created(), result.changed(),
                        result.reusedVectors(), result.embeddedVectors()));
    }

    /** 查询异步入库任务进度（搭配 POST /v1/documents?async=true 使用）。 */
    @GetMapping("/jobs/{jobId}")
    public IngestionService.IngestJobView jobStatus(@PathVariable String jobId) {
        AuthContext.requireCaller();
        return ingestion.getJob(jobId);
    }

    /** 解析质量预检：仅在内存中运行解析器与切分器，不落库、不写向量，返回统计与告警。 */
    @PostMapping(value = "/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public IngestionService.PreviewResult preview(@RequestParam("file") MultipartFile file) {
        AuthContext.requireAdmin();
        if (file.isEmpty()) {
            throw ApiException.badRequest("EMPTY_FILE", "上传文件为空");
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw ApiException.badRequest("READ_FILE_FAILED", "读取上传文件失败：" + e.getMessage());
        }
        String fileName = file.getOriginalFilename() == null ? "upload.bin" : file.getOriginalFilename();
        return ingestion.preview(fileName, bytes);
    }

    /**
     * 直接提交 Markdown 文本（推荐路径：本地用 Docling/MinerU 把 PDF 转好后走这里，
     * 3C4G 服务器就不需要跑重解析模型）。
     */
    @PostMapping(value = "/text", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public ApiDtos.IngestResponse ingestText(@Valid @RequestBody ApiDtos.TextIngestRequest request) {
        AuthContext.requireAdmin();
        String name = (request.docKey() == null || request.docKey().isBlank() ? "inline" : request.docKey()) + ".md";
        IngestionService.IngestResult result = ingestion.ingest(
                request.namespace(), request.docKey(), request.title(), request.url(),
                name, request.content().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return new ApiDtos.IngestResponse(result.document(), result.created(), result.changed(),
                result.reusedVectors(), result.embeddedVectors());
    }

    @GetMapping
    public List<DocumentView> list(@RequestParam(required = false) String namespace,
                                   @RequestParam(defaultValue = "100") int limit) {
        AuthContext.requireCaller();
        return ingestion.list(namespace, limit);
    }

    @DeleteMapping("/{id}")
    public ApiDtos.DeleteResponse delete(@PathVariable long id) {
        AuthContext.requireAdmin();
        int deletedVectors = ingestion.delete(id);
        return new ApiDtos.DeleteResponse(id, deletedVectors);
    }

    /** 查看原文：前端"文档库 → 查看/编辑"用，返回解析后的 Markdown。 */
    @GetMapping("/{id}/content")
    public IngestionService.DocContent content(@PathVariable long id) {
        AuthContext.requireCaller();
        return ingestion.content(id);
    }

    /**
     * 全文替换并重建索引。
     *
     * <p>适合"改一段话再保存"：提交的是完整正文，服务端重新切分、重新写向量，
     * 版本号 +1，检索立刻能看到新内容（旧块在同一个事务里被替换）。
     */
    @PutMapping(value = "/{id}/content", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ApiDtos.IngestResponse replaceContent(@PathVariable long id,
                                                 @Valid @RequestBody ApiDtos.TextContentRequest request) {
        AuthContext.requireAdmin();
        IngestionService.IngestResult result = ingestion.replaceContent(id, request.content(), request.title());
        return new ApiDtos.IngestResponse(result.document(), false, result.changed(),
                result.reusedVectors(), result.embeddedVectors());
    }

    /** 只在末尾追加，不必先读全文再整体回传（Agent 追加 FAQ / 变更记录时更方便）。 */
    @PostMapping(value = "/{id}/append", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ApiDtos.IngestResponse appendContent(@PathVariable long id,
                                                @Valid @RequestBody ApiDtos.TextContentRequest request) {
        AuthContext.requireAdmin();
        IngestionService.IngestResult result = ingestion.appendContent(id, request.content());
        return new ApiDtos.IngestResponse(result.document(), false, result.changed(),
                result.reusedVectors(), result.embeddedVectors());
    }

    /**
     * 按已保存的原文，把整个 namespace 重建一遍索引。
     *
     * <p>刚配好 embedding、或者换了模型/维度之后调一次，历史文档才会补上向量。
     * 没有原文（旧版本入库）的文档会跳过，返回里的 skipped 就是数量。
     */
    @PostMapping("/reindex")
    public IngestionService.ReindexSummary reindex(@RequestParam(required = false) String namespace) {
        AuthContext.requireAdmin();
        return ingestion.reindexAll(namespace);
    }
}
