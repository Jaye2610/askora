// Askora 预设头像库：一组自包含的 SVG data-URI 头像（蓝紫渐变 + 问号光标），
// 不依赖外网图片，保证任何环境都能渲染。存储时把选中的 data-URI 写入 user.avatar 字段。
//
// 问号光标只有一份来源：components/brand/AskoraLogo.tsx 的 askoraMarkSvgBody()。

import { askoraMarkSvgBody } from "@/components/brand/AskoraLogo";

type Gradient = [string, string];

const GRADIENTS: Gradient[] = [
  ["#3B82F6", "#7C3AED"], // 蓝→紫（品牌）
  ["#6366F1", "#8B5CF6"], // 靛→紫
  ["#0EA5E9", "#6366F1"], // 天蓝→靛
  ["#8B5CF6", "#D946EF"], // 紫→粉
  ["#2563EB", "#06B6D4"], // 蓝→青
  ["#7C3AED", "#EC4899"], // 紫→玫红
  ["#4F46E5", "#9333EA"], // 深靛→紫
  ["#0284C7", "#7C3AED"]  // 深蓝→紫
];

function avatarDataUri([from, to]: Gradient): string {
  const svg =
    `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24">` +
    `<defs><linearGradient id="g" x1="0" y1="0" x2="1" y2="1">` +
    `<stop offset="0" stop-color="${from}"/><stop offset="1" stop-color="${to}"/>` +
    `</linearGradient></defs>` +
    `<rect width="24" height="24" rx="12" fill="url(#g)"/>` +
    askoraMarkSvgBody("#fff") +
    `</svg>`;
  return `data:image/svg+xml;utf8,${encodeURIComponent(svg)}`;
}

export const AVATAR_PRESETS: string[] = GRADIENTS.map(avatarDataUri);
