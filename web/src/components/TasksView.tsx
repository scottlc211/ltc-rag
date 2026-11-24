import { useState } from "react";
import { Zap } from "lucide-react";
import { apiRequest, renderMarkdown } from "@/lib/api";

interface TasksViewProps {
  apiKey: string;
  selectedNs: string;
  onUnauthorized: () => void;
  toast: (msg: string, isError?: boolean) => void;
}

export function TasksView({ apiKey, selectedNs, onUnauthorized, toast }: TasksViewProps) {
  const [question, setQuestion] = useState("");
  const [callbackUrl, setCallbackUrl] = useState("");
  const [taskInfo, setTaskInfo] = useState<any | null>(null);
  const [submitting, setSubmitting] = useState(false);

  const submitTask = async () => {
    const q = question.trim();
    if (!q) return toast("请填写问题", true);
    setSubmitting(true);
    try {
      const r = await apiRequest<any>("/v1/tasks", apiKey, {
        method: "POST",
        body: {
          question: q,
          namespaces: selectedNs ? [selectedNs] : [],
          callbackUrl: callbackUrl.trim() || null,
        },
        onUnauthorized,
      });
      setTaskInfo({
        taskId: r.taskId,
        status: r.status,
        callbackSecret: r.callbackSecret,
        answer: null,
        error: null,
      });
      pollTask(r.taskId, r.callbackSecret);
    } catch (e: any) {
      toast("提交失败：" + e.message, true);
    } finally {
      setSubmitting(false);
    }
  };

  const pollTask = async (taskId: string, secret: string | null) => {
    for (let i = 0; i < 120; i++) {
      await new Promise((r) => setTimeout(r, 1500));
      try {
        const t = await apiRequest<any>(`/v1/tasks/${taskId}`, apiKey, { onUnauthorized });
        setTaskInfo({
          taskId: t.taskId,
          status: t.status,
          callbackSecret: secret,
          answer: t.answer,
          error: t.error,
          citations: t.citations || [],
        });
        if (t.status === "SUCCEEDED" || t.status === "FAILED") break;
      } catch (e: any) {
        toast("查询任务失败：" + e.message, true);
        break;
      }
    }
  };

  return (
    <div className="space-y-5 pb-12">
      <div>
        <h2 className="text-xl font-semibold tracking-tight">异步任务</h2>
        <p className="text-xs text-[#8b98a5] mt-1">
          面向外部 Agent 的异步调用模式：提交即返回 taskId，结果通过带 HMAC 签名的 Webhook 回调推送（内置 SSRF 防护），也可直接轮询。
        </p>
      </div>

      <div className="rounded-xl border border-[#22303f] bg-[#111821] p-5 space-y-3">
        <h3 className="text-xs font-medium text-[#8b98a5]">提交异步问答任务</h3>
        <input
          value={question}
          onChange={(e) => setQuestion(e.target.value)}
          placeholder="问题，例如：合同审批流程是什么？"
          className="w-full rounded-lg border border-[#22303f] bg-[#0b0f14] px-3.5 py-2 text-sm outline-none focus:border-[#3fd0c9]"
        />
        <input
          value={callbackUrl}
          onChange={(e) => setCallbackUrl(e.target.value)}
          placeholder="公网回调地址（可选），如 https://your-agent.example.com/hooks/ltc-rag"
          className="w-full rounded-lg border border-[#22303f] bg-[#0b0f14] px-3.5 py-2 text-sm outline-none focus:border-[#3fd0c9]"
        />
        <button
          type="button"
          disabled={submitting}
          onClick={submitTask}
          className="inline-flex items-center gap-1.5 rounded-lg bg-[#3fd0c9] px-4 py-2 text-xs font-semibold text-[#06221f] hover:brightness-110 disabled:opacity-50 cursor-pointer"
        >
          <Zap className="h-3.5 w-3.5" />
          {submitting ? "提交中…" : "提交任务"}
        </button>
      </div>

      {taskInfo && (
        <div className="rounded-xl border border-[#22303f] bg-[#111821] p-5 space-y-3">
          <h3 className="text-xs font-medium text-[#8b98a5]">任务状态</h3>
          <div className="grid grid-cols-[110px_1fr] gap-2 text-xs">
            <span className="text-[#8b98a5]">taskId</span>
            <span className="font-mono text-[#e6edf3]">{taskInfo.taskId}</span>
            <span className="text-[#8b98a5]">状态</span>
            <span className="font-semibold text-[#3fd0c9]">{taskInfo.status}</span>
            <span className="text-[#8b98a5]">回调签名密钥</span>
            <span className="font-mono text-[#8b98a5] break-all">
              {taskInfo.callbackSecret || "（未配置回调地址）"}
            </span>
          </div>
          {taskInfo.answer && (
            <div
              className="md-body mt-3 rounded-xl border border-[#22303f] bg-[#0b0f14] p-4 text-sm leading-relaxed"
              dangerouslySetInnerHTML={{ __html: renderMarkdown(taskInfo.answer) }}
            />
          )}
          {taskInfo.error && (
            <div className="rounded-lg border border-[#ff6b6b]/40 bg-[#ff6b6b]/10 p-3 text-xs text-[#ffc9c9]">
              失败：{taskInfo.error}
            </div>
          )}
        </div>
      )}
    </div>
  );
}
