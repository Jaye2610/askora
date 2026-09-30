import * as React from "react";

import { cn } from "@/lib/utils";

/**
 * Askora 品牌标识的唯一矢量来源。
 *
 * <p>问号光标用纯 path 绘制，不依赖任何字体 —— 这是 favicon 早先用
 * `<text font-family="Arial">?` 时跨平台渲染不一致的根因。
 */
export const ASKORA_MARK_STEM =
  "M9.6 9.2a2.6 2.6 0 1 1 3.2 2.55c-0.9 0.3-0.8 1.1-0.8 1.85";
export const ASKORA_MARK_DOT = { cx: 12, cy: 16.6, r: 1.15 } as const;
export const ASKORA_MARK_RING = { cx: 12, cy: 12, r: 8.4 } as const;
export const ASKORA_BRAND_FROM = "#3B82F6";
export const ASKORA_BRAND_TO = "#7C3AED";

export type AskoraLogoVariant = "mark" | "mark-with-ring" | "wordmark";

interface AskoraLogoProps {
  /** 徽标边长（px）。wordmark 时同时决定文字大小。 */
  size?: number;
  variant?: AskoraLogoVariant;
  className?: string;
  /** 传入后不再是纯装饰，会渲染 <title> 供屏幕阅读器朗读。 */
  title?: string;
}

/** 圆环 + 问号光标，颜色跟随 currentColor。 */
function MarkGlyph({ className }: { className?: string }) {
  return (
    <svg viewBox="0 0 24 24" fill="none" className={className} aria-hidden="true">
      <circle
        cx={ASKORA_MARK_RING.cx}
        cy={ASKORA_MARK_RING.cy}
        r={ASKORA_MARK_RING.r}
        stroke="currentColor"
        strokeWidth="1.4"
        opacity="0.45"
      />
      <path d={ASKORA_MARK_STEM} stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" />
      <circle cx={ASKORA_MARK_DOT.cx} cy={ASKORA_MARK_DOT.cy} r={ASKORA_MARK_DOT.r} fill="currentColor" />
    </svg>
  );
}

export function AskoraLogo({ size = 40, variant = "mark", className, title }: AskoraLogoProps) {
  const gradientId = React.useId();
  const a11y = title ? { role: "img" as const } : { "aria-hidden": true as const };

  const tile = (
    <span
      className={cn(
        "flex shrink-0 items-center justify-center rounded-xl text-white",
        className
      )}
      style={{
        width: size,
        height: size,
        backgroundImage: `linear-gradient(135deg, ${ASKORA_BRAND_FROM} 0%, ${ASKORA_BRAND_TO} 100%)`
      }}
    >
      <svg viewBox="0 0 24 24" fill="none" width="100%" height="100%" {...a11y}>
        {title ? <title>{title}</title> : null}
        <defs>
          <linearGradient id={gradientId} x1="0" y1="0" x2="1" y2="1">
            <stop offset="0" stopColor={ASKORA_BRAND_FROM} />
            <stop offset="1" stopColor={ASKORA_BRAND_TO} />
          </linearGradient>
        </defs>
        <MarkGlyph className="h-full w-full" />
      </svg>
    </span>
  );

  if (variant !== "wordmark") {
    return tile;
  }

  return (
    <span className="flex items-center gap-3">
      {tile}
      <span className="min-w-0">
        <span
          className="block font-semibold leading-tight text-[var(--text-primary)]"
          style={{ fontSize: Math.round(size * 0.4) }}
        >
          Askora AI
        </span>
        <span
          className="block leading-tight text-[var(--text-tertiary)]"
          style={{ fontSize: Math.round(size * 0.28) }}
        >
          企业级智能问答平台
        </span>
      </span>
    </span>
  );
}

/**
 * 供 data-URI / 静态 SVG 复用的内联标记，保证与 <AskoraLogo> 同源。
 * 深色底上使用白色描边。
 */
export function askoraMarkSvgBody(color = "#fff"): string {
  return (
    `<circle cx="${ASKORA_MARK_RING.cx}" cy="${ASKORA_MARK_RING.cy}" r="${ASKORA_MARK_RING.r}" ` +
    `stroke="${color}" stroke-width="1.4" opacity="0.45" fill="none"/>` +
    `<path d="${ASKORA_MARK_STEM}" stroke="${color}" stroke-width="1.7" stroke-linecap="round" fill="none"/>` +
    `<circle cx="${ASKORA_MARK_DOT.cx}" cy="${ASKORA_MARK_DOT.cy}" r="${ASKORA_MARK_DOT.r}" fill="${color}"/>`
  );
}
