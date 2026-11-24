import { useEffect, useState } from "react";
import { Copy, RefreshCw, Zap, KeyRound, ShieldCheck, Ban, CheckCircle2 } from "lucide-react";
import { ChatConfigView, KeySummary, NamespaceStat, apiRequest } from "@/lib/api";

interface AdminViewProps {
  apiKey: string;
  namespaces: NamespaceStat[];
  onModelUpdated: (model: string) => void;
  onUnauthorized: () => void;
  toast: (msg: string, isError?: boolean) => void;
}

const PROTOCOLS = [
  { id: "openai", label: "OpenAI 兼容" },
  { id: "openai-responses", label: "OpenAI Responses" },
  { id: "anthropic", label: "Anthropic 兼容" },
  { id: "gemini", label: "Gemini 兼容" },
];

export function AdminView({
  apiKey,
  namespaces,
  onModelUpdated,
  onUnauthorized,
  toast,
}: AdminViewProps) {
  // 大模型配置
  const [protocol, setProtocol] = useState("openai");
  const [baseUrl, setBaseUrl] = useState("");
  const [model, setModel] = useState("");
  const [llmApiKey, setLlmApiKey] = useState("");
  const [showKey, setShowKey] = useState(false);
  const [cfgStatus, setCfgStatus] = useState("—");
  const [fetchedModels, setFetchedModels] = useState<string[]>([]);
  const [probing, setProbing] = useState(false);
  const [fetchingModels, setFetchingModels] = useState(false);

  // 角色与密钥签发
  const [newKeyName, setNewKeyName] = useState("");
  const [newKeyRole, setNewKeyRole] = useState("AGENT");
  const [newKeyQuota, setNewKeyQuota] = useState("");
  const [selectedAllowedNs, setSelectedAllowedNs] = useState<string[]>([]);
  const [issuedKey, setIssuedKey] = useState<string | null>(null);
  const [keysList, setKeysList] = useState<KeySummary[]>([]);

  // 统计
  const [dailyStats, setDailyStats] = useState<any[]>([]);
  const [callbackStat, setCallbackStat] = useState("");

  const loadAll = async () => {
    try {
      const cfg = await apiRequest<ChatConfigView>("/v1/admin/chat-config", apiKey, {
        onUnauthorized,
      });
      setProtocol(cfg.protocol || "openai");
      setBaseUrl(cfg.baseUrl || "");
      setModel(cfg.model || "");
      setCfgStatus(
        cfg.configured
          ? `当前生效：[${cfg.protocol || "openai"}] ${cfg.model}（密钥 ${cfg.apiKeyMasked}）`
          : "尚未配置 CHAT_API_KEY"
      );
    } catch {
      /* ignore */
    }

    try {
      const list = await apiRequest<KeySummary[]>("/v1/admin/keys", apiKey, { onUnauthorized });
      setKeysList(list);
    } catch {
      /* ignore */
    }

    try {
      const s = await apiRequest<any>("/v1/admin/stats", apiKey, { onUnauthorized });
      setDailyStats(s.daily || []);
      setCallbackStat(`回调队列：待发送 ${s.callbackPending} 条，死信 ${s.callbackFailed} 条`);
    } catch {
      /* ignore */
    }
  };

  useEffect(() => {
    loadAll();
  }, []);

  const handleFetchModels = async () => {
    setFetchingModels(true);
    try {
      const r = await apiRequest<{ models: string[] }>("/v1/admin/chat-config/models", apiKey, {
        method: "POST",
        body: { protocol, baseUrl: baseUrl.trim(), apiKey: llmApiKey.trim() || null },
        onUnauthorized,
      });
      const list = r.models || [];
      setFetchedModels(list);
      if (list.length && !model.trim()) setModel(list[0]);
      toast(`已获取 ${list.length} 个可用模型，点击下方标签即可选用`);
    } catch (e: any) {
      toast("获取模型失败：" + e.message, true);
    } finally {
      setFetchingModels(false);
    }
  };

  const handleProbe = async () => {
    setProbing(true);
    try {
      const r = await apiRequest<any>("/v1/admin/chat-config/probe", apiKey, {
        method: "POST",
        body: {
          baseUrl: baseUrl.trim(),
          apiKey: llmApiKey.trim() || null,
          model: model.trim() || null,
        },
        onUnauthorized,
      });
      if (r.models?.length) setFetchedModels(r.models);
      if (r.success) {
        setProtocol(r.protocol);
        if (r.model) setModel(r.model);
        toast(`检测通过！协议: ${r.protocol}，测试模型: ${r.model}`);
      } else {
        toast("检测未通过：" + (r.message || "请检查配置"), true);
      }
    } catch (e: any) {
      toast("协议检测失败：" + e.message, true);
    } finally {
      setProbing(false);
    }
  };

  const handleSaveChatConfig = async () => {
    if (!model.trim()) return toast("请填写或选择模型 ID", true);
    try {
      const cfg = await apiRequest<ChatConfigView>("/v1/admin/chat-config", apiKey, {
        method: "PUT",
        body: {
          protocol,
          baseUrl: baseUrl.trim(),
          model: model.trim(),
          apiKey: llmApiKey.trim() || null,
        },
        onUnauthorized,
      });
      setLlmApiKey("");
      setCfgStatus(`已生效：[${cfg.protocol}] ${cfg.model}（密钥 ${cfg.apiKeyMasked}）`);
      onModelUpdated(cfg.model);
      toast("模型配置已更新并立即生效：" + cfg.model);
    } catch (e: any) {
      toast("保存失败：" + e.message, true);
    }
  };

  const toggleNsCheckbox = (ns: string) => {
    setSelectedAllowedNs((prev) =>
      prev.includes(ns) ? prev.filter((x) => x !== ns) : [...prev, ns]
    );
  };

  const handleIssueKey = async () => {
    const name = newKeyName.trim() || "agent";
    const quota = newKeyQuota.trim() ? Number(newKeyQuota.trim()) : null;
    try {
      const r = await apiRequest<any>("/v1/admin/keys", apiKey, {
        method: "POST",
        body: {
          name,
          role: newKeyRole,
          quotaPerDay: quota && quota > 0 ? quota : null,
          allowedNamespaces: newKeyRole === "AGENT" ? selectedAllowedNs : [],
        },
        onUnauthorized,
      });
      setIssuedKey(r.key);
      setNewKeyName("");
      setNewKeyQuota("");
      setSelectedAllowedNs([]);
      toast("已生成新 Key，请立即复制保存");
      loadAll();
    } catch (e: any) {
      toast("签发失败：" + e.message, true);
    }
  };

  const toggleKeyStatus = async (k: KeySummary) => {
    const nextStatus = k.status === "ACTIVE" ? "DISABLED" : "ACTIVE";
    try {
      await apiRequest(`/v1/admin/keys/${k.id}`, apiKey, {
        method: "PATCH",
        body: { status: nextStatus, role: k.role, allowedNamespaces: k.allowedNamespaces },
        onUnauthorized,
      });
      toast(`已${nextStatus === "ACTIVE" ? "启用" : "停用"} ${k.name}`);
      loadAll();
    } catch (e: any) {
      toast("操作失败：" + e.message, true);
    }
  };

  return (
    <div className="space-y-6 pb-12">
      <div>
        <h2 className="text-xl font-semibold tracking-tight">密钥、角色权限与模型设置</h2>
        <p className="text-xs text-[#8b98a5] mt-1">
          支持多协议大模型在线热切换；支持按角色（ADMIN / AGENT）及指定命名空间签发与管理访问密钥。
        </p>
      </div>

      {/* 1. 大模型多协议配置（Magpie 同款） */}
      <div className="rounded-xl border border-[#22303f] bg-[#111821] p-5 space-y-3.5">
        <div className="flex flex-wrap items-center justify-between gap-2">
          <h3 className="text-sm font-medium text-[#e6edf3]">大模型配置（多协议兼容 · 免重启）</h3>
          <button
            type="button"
            disabled={probing}
            onClick={handleProbe}
            className="inline-flex items-center gap-1 rounded-lg border border-[#22303f] px-3 py-1.5 text-xs text-[#e6edf3] hover:border-[#3fd0c9] hover:text-[#3fd0c9] cursor-pointer"
          >
            <Zap className="h-3.5 w-3.5" />
            {probing ? "检测中…" : "检测协议并测活"}
          </button>
        </div>

        <div className="flex flex-wrap items-center gap-2">
          <span className="text-xs text-[#8b98a5]">接口协议</span>
          <div className="inline-flex flex-wrap gap-1 rounded-lg border border-[#22303f] bg-[#16202c] p-1">
            {PROTOCOLS.map((p) => (
              <button
                key={p.id}
                type="button"
                onClick={() => setProtocol(p.id)}
                className={`rounded-md px-3 py-1 text-xs transition cursor-pointer ${
                  protocol === p.id
                    ? "bg-[#3fd0c9]/20 font-semibold text-[#3fd0c9]"
                    : "text-[#8b98a5] hover:text-white"
                }`}
              >
                {p.label}
              </button>
            ))}
          </div>
        </div>

        <input
          value={baseUrl}
          onChange={(e) => setBaseUrl(e.target.value)}
          placeholder="Base URL，例如 https://moxuna.com/v1 或 http://magpie:3425/v1"
          className="w-full rounded-lg border border-[#22303f] bg-[#0b0f14] px-3.5 py-2 text-xs outline-none focus:border-[#3fd0c9]"
        />

        <div className="flex gap-2">
          <input
            type={showKey ? "text" : "password"}
            value={llmApiKey}
            onChange={(e) => setLlmApiKey(e.target.value)}
            placeholder="API 密钥（留空则保持已保存密钥不变）"
            autoComplete="off"
            className="flex-1 rounded-lg border border-[#22303f] bg-[#0b0f14] px-3.5 py-2 text-xs outline-none focus:border-[#3fd0c9]"
          />
          <button
            type="button"
            onClick={() => setShowKey((v) => !v)}
            className="rounded-lg border border-[#22303f] px-3 py-1.5 text-xs text-[#8b98a5] hover:text-white cursor-pointer"
          >
            {showKey ? "隐藏" : "显示"}
          </button>
        </div>

        <div className="flex gap-2">
          <input
            value={model}
            onChange={(e) => setModel(e.target.value)}
            placeholder="模型 ID，可手动输入或点右侧「获取模型」"
            className="flex-1 rounded-lg border border-[#22303f] bg-[#0b0f14] px-3.5 py-2 text-xs outline-none focus:border-[#3fd0c9]"
          />
          <button
            type="button"
            disabled={fetchingModels}
            onClick={handleFetchModels}
            className="inline-flex items-center gap-1 rounded-lg border border-[#22303f] px-3 py-1.5 text-xs text-[#e6edf3] hover:border-[#3fd0c9] hover:text-[#3fd0c9] cursor-pointer"
          >
            <RefreshCw className={`h-3.5 w-3.5 ${fetchingModels ? "animate-spin" : ""}`} />
            获取模型
          </button>
        </div>

        {fetchedModels.length > 0 && (
          <div className="flex flex-wrap gap-1.5 max-h-32 overflow-y-auto pt-1">
            {fetchedModels.map((m) => (
              <button
                key={m}
                type="button"
                onClick={() => setModel(m)}
                className={`rounded-full border px-2.5 py-1 text-xs transition cursor-pointer ${
                  model === m
                    ? "border-[#3fd0c9] bg-[#3fd0c9]/15 text-[#3fd0c9]"
                    : "border-[#22303f] bg-[#16202c] text-[#e6edf3] hover:border-[#3fd0c9]/60"
                }`}
              >
                {m}
              </button>
            ))}
          </div>
        )}

        <div className="flex flex-wrap items-center gap-3 pt-1">
          <button
            type="button"
            onClick={handleSaveChatConfig}
            className="rounded-lg bg-[#3fd0c9] px-4 py-2 text-xs font-semibold text-[#06221f] hover:brightness-110 cursor-pointer"
          >
            保存模型配置
          </button>
          <span className="text-xs text-[#8b98a5]">{cfgStatus}</span>
        </div>
      </div>

      {/* 2. 角色与命名空间权限密钥签发 */}
      <div className="rounded-xl border border-[#22303f] bg-[#111821] p-5 space-y-4">
        <div className="flex items-center gap-2">
          <KeyRound className="h-4 w-4 text-[#3fd0c9]" />
          <h3 className="text-sm font-medium text-[#e6edf3]">签发角色密钥（支持按命名空间授权）</h3>
        </div>

        <div className="grid grid-cols-1 md:grid-cols-3 gap-2.5">
          <input
            value={newKeyName}
            onChange={(e) => setNewKeyName(e.target.value)}
            placeholder="名称，如 sales-readonly / hr-bot"
            className="rounded-lg border border-[#22303f] bg-[#0b0f14] px-3 py-2 text-xs outline-none focus:border-[#3fd0c9]"
          />
          <select
            value={newKeyRole}
            onChange={(e) => setNewKeyRole(e.target.value)}
            className="rounded-lg border border-[#22303f] bg-[#0b0f14] px-3 py-2 text-xs outline-none focus:border-[#3fd0c9]"
          >
            <option value="AGENT">AGENT（只读问答，不可修改/上传）</option>
            <option value="ADMIN">ADMIN（管理员，全部权限）</option>
          </select>
          <input
            type="number"
            min={0}
            value={newKeyQuota}
            onChange={(e) => setNewKeyQuota(e.target.value)}
            placeholder="每日问答配额（留空=不限）"
            className="rounded-lg border border-[#22303f] bg-[#0b0f14] px-3 py-2 text-xs outline-none focus:border-[#3fd0c9]"
          />
        </div>

        {newKeyRole === "AGENT" && (
          <div className="rounded-lg border border-[#22303f] bg-[#0b0f14] p-3">
            <div className="text-xs text-[#8b98a5] mb-2">
              授权可见命名空间（<strong>不勾选表示可访问全部空间</strong>；勾选后该登录人仅能看到并检索以下空间）：
            </div>
            <div className="flex flex-wrap gap-2">
              {namespaces.map((ns) => {
                const checked = selectedAllowedNs.includes(ns.namespace);
                return (
                  <button
                    key={ns.namespace}
                    type="button"
                    onClick={() => toggleNsCheckbox(ns.namespace)}
                    className={`rounded-full border px-3 py-1 text-xs transition cursor-pointer ${
                      checked
                        ? "border-[#3fd0c9] bg-[#3fd0c9]/15 text-[#3fd0c9] font-medium"
                        : "border-[#22303f] bg-[#16202c] text-[#8b98a5] hover:text-white"
                    }`}
                  >
                    {checked ? "✓ " : "+ "}
                    {ns.namespace}（{ns.docs} 篇）
                  </button>
                );
              })}
            </div>
          </div>
        )}

        <button
          type="button"
          onClick={handleIssueKey}
          className="rounded-lg bg-[#3fd0c9] px-4 py-2 text-xs font-semibold text-[#06221f] hover:brightness-110 cursor-pointer"
        >
          生成密钥
        </button>

        {issuedKey && (
          <div className="rounded-xl border border-[#3fd0c9]/40 bg-[#3fd0c9]/10 p-3.5 space-y-2">
            <div className="flex items-center justify-between gap-2">
              <span className="font-mono text-xs text-[#e6edf3] break-all">{issuedKey}</span>
              <button
                type="button"
                onClick={async () => {
                  await navigator.clipboard.writeText(issuedKey);
                  toast("已复制 API Key");
                }}
                className="inline-flex shrink-0 items-center gap-1 rounded-md border border-[#3fd0c9]/40 px-2.5 py-1 text-xs text-[#3fd0c9] hover:bg-[#3fd0c9]/15 cursor-pointer"
              >
                <Copy className="h-3.5 w-3.5" />
                复制
              </button>
            </div>
            <div className="text-[11px] text-[#8b98a5]">
              明文只显示这一次，请立即保存。一键免输登录链接：
              <code className="ml-1 text-[#7aa2ff]">
                {window.location.origin}/#k={issuedKey}
              </code>
            </div>
          </div>
        )}

        {/* 已签发密钥列表 */}
        <div className="overflow-x-auto rounded-lg border border-[#22303f]">
          <table className="w-full text-left text-xs border-collapse">
            <thead>
              <tr className="border-b border-[#22303f] bg-[#16202c] text-[#8b98a5]">
                <th className="px-3 py-2.5 font-medium">名称</th>
                <th className="px-3 py-2.5 font-medium">前缀</th>
                <th className="px-3 py-2.5 font-medium">角色</th>
                <th className="px-3 py-2.5 font-medium">授权命名空间</th>
                <th className="px-3 py-2.5 font-medium">状态</th>
                <th className="px-3 py-2.5 font-medium text-right">操作</th>
              </tr>
            </thead>
            <tbody>
              {keysList.map((k) => (
                <tr key={k.id} className="border-b border-[#22303f]/60">
                  <td className="px-3 py-2 font-medium text-[#e6edf3]">{k.name}</td>
                  <td className="px-3 py-2 font-mono text-[#8b98a5]">{k.keyPrefix}…</td>
                  <td className="px-3 py-2">
                    <span
                      className={`inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-[11px] ${
                        k.role === "ADMIN"
                          ? "bg-[#7aa2ff]/15 text-[#7aa2ff]"
                          : "bg-[#3fd0c9]/15 text-[#3fd0c9]"
                      }`}
                    >
                      <ShieldCheck className="h-3 w-3" />
                      {k.role}
                    </span>
                  </td>
                  <td className="px-3 py-2 text-[#8b98a5]">
                    {k.role === "ADMIN" || !k.allowedNamespaces?.length
                      ? "全部空间"
                      : k.allowedNamespaces.join(", ")}
                  </td>
                  <td className="px-3 py-2">
                    <span
                      className={`text-[11px] ${
                        k.status === "ACTIVE" ? "text-[#4ade80]" : "text-[#ff6b6b]"
                      }`}
                    >
                      {k.status === "ACTIVE" ? "启用中" : "已停用"}
                    </span>
                  </td>
                  <td className="px-3 py-2 text-right">
                    <button
                      type="button"
                      onClick={() => toggleKeyStatus(k)}
                      className="inline-flex items-center gap-1 rounded-md border border-[#22303f] px-2 py-1 text-[11px] text-[#8b98a5] hover:border-[#3fd0c9] hover:text-white cursor-pointer"
                    >
                      {k.status === "ACTIVE" ? (
                        <>
                          <Ban className="h-3 w-3 text-[#ff6b6b]" />
                          停用
                        </>
                      ) : (
                        <>
                          <CheckCircle2 className="h-3 w-3 text-[#4ade80]" />
                          启用
                        </>
                      )}
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>

      {/* 3. 调用统计 */}
      <div className="rounded-xl border border-[#22303f] bg-[#111821] p-5 space-y-3">
        <h3 className="text-sm font-medium text-[#e6edf3]">调用统计（近 30 天）</h3>
        <div className="overflow-x-auto rounded-lg border border-[#22303f]">
          <table className="w-full text-left text-xs border-collapse">
            <thead>
              <tr className="border-b border-[#22303f] bg-[#16202c] text-[#8b98a5]">
                <th className="px-4 py-2.5 font-medium">日期</th>
                <th className="px-4 py-2.5 font-medium">调用次数</th>
                <th className="px-4 py-2.5 font-medium">引用命中总数</th>
              </tr>
            </thead>
            <tbody>
              {dailyStats.length === 0 ? (
                <tr>
                  <td colSpan={3} className="px-4 py-6 text-center text-[#8b98a5]">
                    还没有调用记录
                  </td>
                </tr>
              ) : (
                dailyStats.map((r, i) => (
                  <tr key={i} className="border-b border-[#22303f]/60">
                    <td className="px-4 py-2">{String(r.day).slice(0, 10)}</td>
                    <td className="px-4 py-2">{r.calls}</td>
                    <td className="px-4 py-2">{r.hits ?? 0}</td>
                  </tr>
                ))
              )}
            </tbody>
          </table>
        </div>
        {callbackStat && <div className="text-xs text-[#8b98a5]">{callbackStat}</div>}
      </div>
    </div>
  );
}
