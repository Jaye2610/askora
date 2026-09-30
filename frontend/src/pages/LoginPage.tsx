import * as React from "react";
import { Brain, Eye, EyeOff, Lock, Search, ShieldCheck, User } from "lucide-react";
import { useNavigate } from "react-router-dom";

import { AskoraLogo } from "@/components/brand/AskoraLogo";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Checkbox } from "@/components/ui/checkbox";
import { useAuthStore } from "@/stores/authStore";

export function LoginPage() {
  const navigate = useNavigate();
  const { login, isLoading } = useAuthStore();
  const [showPassword, setShowPassword] = React.useState(false);
  const [remember, setRemember] = React.useState(true);
  const [form, setForm] = React.useState({ username: "admin", password: "admin" });
  const [error, setError] = React.useState<string | null>(null);

  const handleSubmit = async (event: React.FormEvent) => {
    event.preventDefault();
    setError(null);
    if (!form.username.trim() || !form.password.trim()) {
      setError("请输入用户名和密码。");
      return;
    }
    try {
      await login(form.username.trim(), form.password.trim());
      if (!remember) {
        // 如需仅在内存中保存登录态，可在此扩展。
      }
      navigate("/chat");
    } catch (err) {
      setError((err as Error).message || "登录失败，请稍后重试。");
    }
  };

  const features = [
    { icon: Search, title: "多路混合检索", desc: "向量 + 关键词 + 图谱融合召回" },
    { icon: Brain, title: "深度思考问答", desc: "结构化推理与可溯源回答" },
    { icon: ShieldCheck, title: "企业级稳定", desc: "限流熔断、全链路可观测" }
  ];

  return (
    <div className="relative flex min-h-screen items-center justify-center overflow-hidden px-4 py-10 sm:px-6">
      {/* 背景光斑 */}
      <div aria-hidden="true" className="pointer-events-none absolute inset-0 bg-gradient-to-br from-slate-50 via-blue-50/40 to-violet-100 dark:from-slate-950 dark:via-slate-900 dark:to-slate-900" />
      <div aria-hidden="true" className="pointer-events-none absolute -top-40 right-[-80px] h-96 w-96 rounded-full bg-gradient-radial from-violet-300/40 via-transparent to-transparent blur-3xl" />
      <div aria-hidden="true" className="pointer-events-none absolute -bottom-44 left-[-100px] h-[28rem] w-[28rem] rounded-full bg-gradient-radial from-blue-300/30 via-transparent to-transparent blur-3xl" />

      <div className="relative z-10 grid w-full max-w-5xl gap-8 lg:grid-cols-[1.1fr_1fr] lg:gap-12">
        {/* 左侧品牌区 */}
        <div className="hidden flex-col justify-center lg:flex">
          <div className="flex items-center gap-4">
            <AskoraLogo size={56} title="Askora AI" className="!rounded-2xl shadow-xl" />
            <div>
              <p className="font-display text-3xl font-bold tracking-tight text-[var(--text-primary)]">Askora AI</p>
              <p className="text-sm text-muted-foreground">企业级智能问答平台</p>
            </div>
          </div>
          <h1 className="mt-8 font-display text-4xl leading-tight tracking-tight text-gray-900">
            把问题变成
            <span className="text-gradient">清晰答案</span>
          </h1>
          <p className="mt-4 max-w-md text-base leading-relaxed text-gray-500">
            面向企业内部知识管理场景，覆盖文档入库、多路检索、智能问答到答案溯源的全链路。
          </p>
          <div className="mt-10 space-y-5">
            {features.map((f) => {
              const Icon = f.icon;
              return (
                <div key={f.title} className="flex items-start gap-4">
                  <span className="flex h-10 w-10 shrink-0 items-center justify-center rounded-xl bg-gradient-to-br from-blue-500/10 to-violet-500/10 text-violet-600">
                    <Icon className="h-5 w-5" />
                  </span>
                  <div>
                    <p className="font-medium text-gray-800">{f.title}</p>
                    <p className="text-sm text-gray-500">{f.desc}</p>
                  </div>
                </div>
              );
            })}
          </div>
        </div>

        {/* 右侧登录卡片 */}
        <div className="flex w-full items-center justify-center">
          <div className="w-full max-w-md rounded-3xl border border-border/70 bg-background/80 p-8 shadow-soft backdrop-blur">
            <div className="mb-6 flex items-center gap-3 lg:hidden">
              <AskoraLogo size={44} title="Askora AI" className="!rounded-2xl shadow-lg" />
              <p className="font-display text-xl font-semibold tracking-tight text-[var(--text-primary)]">Askora AI</p>
            </div>
            <div className="mb-6">
              <p className="font-display text-2xl font-semibold leading-tight">欢迎回来</p>
              <p className="mt-0.5 text-sm text-muted-foreground">登录后继续你的检索增强对话。</p>
            </div>
            <form className="space-y-4" onSubmit={handleSubmit}>
              <div className="space-y-2">
                <label className="text-xs font-semibold uppercase tracking-wide text-muted-foreground">用户名</label>
                <div className="relative">
                  <User className="absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
                  <Input
                    placeholder="请输入用户名"
                    value={form.username}
                    onChange={(event) => setForm((prev) => ({ ...prev, username: event.target.value }))}
                    className="pl-10"
                    autoComplete="username"
                  />
                </div>
              </div>
              <div className="space-y-2">
                <label className="text-xs font-semibold uppercase tracking-wide text-muted-foreground">密码</label>
                <div className="relative">
                  <Lock className="absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
                  <Input
                    type={showPassword ? "text" : "password"}
                    placeholder="请输入密码"
                    value={form.password}
                    onChange={(event) => setForm((prev) => ({ ...prev, password: event.target.value }))}
                    className="pl-10 pr-10"
                    autoComplete="current-password"
                  />
                  <button
                    type="button"
                    onClick={() => setShowPassword((prev) => !prev)}
                    className="absolute right-3 top-1/2 -translate-y-1/2 text-muted-foreground"
                    aria-label="显示或隐藏密码"
                  >
                    {showPassword ? <EyeOff className="h-4 w-4" /> : <Eye className="h-4 w-4" />}
                  </button>
                </div>
              </div>
              <div className="flex items-center justify-between text-sm">
                <label className="flex items-center gap-2 text-muted-foreground">
                  <Checkbox checked={remember} onCheckedChange={(value) => setRemember(Boolean(value))} />
                  记住我
                </label>
                <span className="text-xs text-muted-foreground">账号由管理员初始化</span>
              </div>
              {error ? <p className="text-sm text-destructive">{error}</p> : null}
              <Button type="submit" className="w-full bg-gradient-to-br from-blue-500 to-violet-600 hover:from-blue-600 hover:to-violet-700" disabled={isLoading}>
                {isLoading ? "正在登录..." : "登录"}
              </Button>
            </form>
          </div>
        </div>
      </div>
    </div>
  );
}
