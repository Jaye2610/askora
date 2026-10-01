import * as React from "react";

import { cn } from "@/lib/utils";

/**
 * Askora 品牌标识的唯一定义处。
 *
 * 设计约束：同一个标识要同时活在浅底和暗底上，所以**不写死任何颜色** ——
 * 形状靠 currentColor / --brand-mark，色块靠 --brand-from / --brand-to
 * （这两个在 .dark 里各有一套值）。
 *
 * 字形用纯 path 描边，不涉及任何字体 —— 早先 favicon 用
 * `<text font-family="Arial">?` 时跨平台渲染不一致，根源就是字体依赖。
 */
/**
 * 字形几何。
 *
 * 软化处理：纯 `L` 连成的 A 顶点是尖角，而 `stroke-linejoin: round`
 * 只能磨掉约 strokeWidth/2（≈1px）的外角，改不了尖角本身。
 * 所以这里三处都换成曲线：
 *   - 双腿用三次贝塞尔做微凸外弧（不再笔直）
 *   - 顶点用二次曲线做圆肩（不再是尖角）
 *   - 横杠略带弧度，与腿的弧度呼应
 */
export const ASKORA_MARK_D =
  "M8.4 17.6 C9.8 13.4 10.9 9.7 11.5 7.7 Q12 6.3 12.5 7.7 C13.1 9.7 14.2 13.4 15.6 17.6 M9.8 13.2 Q12 12.4 14.2 13.2";
export const ASKORA_MARK_STROKE = 2.1;

export type AskoraLogoVariant = "mark" | "plain" | "wordmark";

interface AskoraLogoProps {
  /** 徽标边长（px）。wordmark 时同时决定文字大小。 */
  size?: number;
  variant?: AskoraLogoVariant;
  className?: string;
  /** 传入后不再是纯装饰，会渲染 <title> 供屏幕阅读器朗读。 */
  title?: string;
}

function MarkGlyph({ className, color }: { className?: string; color?: string }) {
  return (
    <svg viewBox="0 0 24 24" fill="none" className={className} aria-hidden="true">
      <path
        d={ASKORA_MARK_D}
        stroke={color ?? "currentColor"}
        strokeWidth={ASKORA_MARK_STROKE}
        strokeLinecap="round"
        strokeLinejoin="round"
      />
    </svg>
  );
}

export function AskoraLogo({ size = 40, variant = "mark", className, title }: AskoraLogoProps) {
  const gradientId = React.useId();
  const a11y = title ? { role: "img" as const } : { "aria-hidden": true as const };

  // plain：不带色块，字形跟随 currentColor。用于彩色底 / 单色印刷场景
  if (variant === "plain") {
    return (
      <svg viewBox="0 0 24 24" fill="none" width={size} height={size} className={className} {...a11y}>
        {title ? <title>{title}</title> : null}
        <path
          d={ASKORA_MARK_D}
          stroke="currentColor"
          strokeWidth={ASKORA_MARK_STROKE}
          strokeLinecap="round"
          strokeLinejoin="round"
        />
      </svg>
    );
  }

  const tile = (
    <span
      className={cn("flex shrink-0 items-center justify-center rounded-xl", className)}
      style={{
        width: size,
        height: size,
        backgroundImage: `linear-gradient(135deg, var(--brand-from) 0%, var(--brand-to) 100%)`
      }}
    >
      <svg viewBox="0 0 24 24" fill="none" width="100%" height="100%" {...a11y}>
        {title ? <title>{title}</title> : null}
        <defs>
          <linearGradient id={gradientId} x1="0" y1="0" x2="1" y2="1">
            <stop offset="0" stopColor="var(--brand-from)" />
            <stop offset="1" stopColor="var(--brand-to)" />
          </linearGradient>
        </defs>
        <MarkGlyph className="h-full w-full" color="var(--brand-mark)" />
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

/** 供静态 SVG / data-URI 复用，保证与组件同源。 */
export function askoraMarkSvgBody(color = "#fff", stroke = ASKORA_MARK_STROKE): string {
  return `<path d="${ASKORA_MARK_D}" stroke="${color}" stroke-width="${stroke}" stroke-linecap="round" stroke-linejoin="round" fill="none"/>`;
}
