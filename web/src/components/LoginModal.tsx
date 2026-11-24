import { useState } from "react";
import { KeyRound, X, Loader2 } from "lucide-react";

interface LoginModalProps {
  open: boolean;
  onClose: () => void;
  onSubmit: (key: string) => Promise<void>;
}

export function LoginModal({ open, onClose, onSubmit }: LoginModalProps) {
  const [key, setKey] = useState("");
  const [loading, setLoading] = useState(false);

  if (!open) return null;

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    const trimmed = key.trim();
    if (!trimmed) return;
    setLoading(true);
    try {
      await onSubmit(trimmed);
    } finally {
      setLoading(false);
    }
  };

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 backdrop-blur-sm p-4"
      onClick={onClose}
    >
      <div
        className="w-full max-w-md rounded-2xl border border-[#22303f] bg-[#111821] p-7 shadow-2xl text-[#e6edf3]"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="flex items-center justify-between mb-2">
          <div className="flex items-center gap-2.5">
            <span className="flex h-9 w-9 items-center justify-center rounded-xl bg-[#2e7d5b]/20 text-[#3fd0c9] border border-[#3fd0c9]/30">
              <KeyRound className="h-4 w-4" />
            </span>
            <h2 className="text-lg font-semibold tracking-tight">进入 LTC 知识库</h2>
          </div>
          <button
            type="button"
            onClick={onClose}
            className="rounded-lg p-1.5 text-[#8b98a5] hover:bg-[#16202c] hover:text-white transition"
          >
            <X className="h-4 w-4" />
          </button>
        </div>

        <p className="text-xs text-[#8b98a5] mb-5 leading-relaxed">
          输入您的 API Key。系统将自动识别角色（<span className="text-[#7aa2ff]">ADMIN</span> 可管理入库，
          <span className="text-[#3fd0c9]">AGENT</span> 仅可按授权命名空间问答与查阅）。
        </p>

        <form onSubmit={handleSubmit} className="space-y-4">
          <input
            type="password"
            value={key}
            onChange={(e) => setKey(e.target.value)}
            placeholder="ltc_..."
            autoFocus
            autoComplete="off"
            spellCheck={false}
            className="w-full rounded-xl border border-[#22303f] bg-[#0b0f14] px-3.5 py-2.5 text-sm text-[#e6edf3] placeholder-[#6b7785] outline-none focus:border-[#3fd0c9] focus:ring-2 focus:ring-[#3fd0c9]/20"
          />
          <button
            type="submit"
            disabled={loading || !key.trim()}
            className="flex w-full items-center justify-center gap-2 rounded-xl bg-[#3fd0c9] px-4 py-2.5 text-sm font-semibold text-[#06221f] hover:brightness-110 disabled:opacity-50 transition cursor-pointer"
          >
            {loading && <Loader2 className="h-4 w-4 animate-spin" />}
            {loading ? "验证中…" : "验证并进入工作台"}
          </button>
        </form>

        <p className="mt-4 text-[11px] text-[#6b7785]">
          Key 仅保存在当前浏览器 localStorage 中，支持通过 URL <code>#k=...</code> 免手输快捷登录。
        </p>
      </div>
    </div>
  );
}
