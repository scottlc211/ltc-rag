package com.ltc.rag.api;

import com.ltc.rag.auth.AuthContext;
import com.ltc.rag.service.TaskService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 异步任务接口：提交 → 立即拿到 taskId → 回调推送结果（也可轮询兜底）。
 *
 * <p>为什么要有异步：Agent 的调用链常见 60s+ 的网关超时，长回答很容易被掐断；
 * 异步 + 回调把"等待"从调用链上摘掉。
 */
@RestController
@RequestMapping("/v1/tasks")
public class TaskController {

    public record SubmitBody(
            @NotBlank(message = "question 不能为空") String question,
            List<String> namespaces,
            String callbackUrl,
            Map<String, Object> metadata
    ) {
    }

    private final TaskService taskService;

    public TaskController(TaskService taskService) {
        this.taskService = taskService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public TaskService.Submitted submit(@Valid @RequestBody SubmitBody body) {
        AuthContext.requireCaller();
        return taskService.submit(new TaskService.SubmitRequest(
                body.question(), body.namespaces(), body.callbackUrl(), body.metadata()));
    }

    @GetMapping("/{taskId}")
    public TaskService.TaskView get(@PathVariable String taskId) {
        return taskService.get(taskId);
    }

    /** 死信重放：回调最终失败后，人工触发一次重投。 */
    @PostMapping("/{taskId}/callback/retry")
    public Map<String, Object> retryCallback(@PathVariable String taskId) {
        taskService.retryCallback(taskId);
        return Map.of("taskId", taskId, "status", "RESCHEDULED");
    }
}
