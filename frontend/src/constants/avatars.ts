// 头像的唯一出口。
//
// 真正的生成逻辑在 pixelAvatars.ts（字符网格 → data-URI）。
// 这里只做两件事：转出预设、以及**把不可信的外链挡掉**。
//
// 为什么需要 resolveAvatar：
// 数据库 t_user.avatar 里可能存着任意 URL —— 实际见过一条管理员记录存的是
// https://static.deepseek.com/user-avatar/... 这类第三方 CDN 外链。
// 前端若直接 <img src={user.avatar}>，就会把外部资源渲染出来，
// 而且一旦对方删图/挂掉，页面就多一个裂图。
// 所以渲染一律走 resolveAvatar：只认「本站相对路径」与「data:」，
// 其余一律回落到默认头像。

import {
  AVATAR_PRESETS,
  DEFAULT_ADMIN_AVATAR,
  CLOTHING,
  CLOTHING_LIST,
  SPECIES_LIST,
  pixelAvatar,
  type ClothingName,
  type SpeciesName
} from "@/constants/pixelAvatars";

export {
  AVATAR_PRESETS,
  DEFAULT_ADMIN_AVATAR,
  CLOTHING,
  CLOTHING_LIST,
  SPECIES_LIST,
  pixelAvatar
};
export type { ClothingName, SpeciesName };

/**
 * 把任意来源的头像值规范化成可安全渲染的 src。
 *   - 空值                -> 默认头像
 *   - data: / blob:       -> 原样（预设头像就是 data-URI）
 *   - 以 / 开头的相对路径 -> 原样（本站静态资源）
 *   - 其余（外链、协议相对等） -> 默认头像
 */
export function resolveAvatar(value?: string | null, fallback: string = DEFAULT_ADMIN_AVATAR): string {
  const v = value?.trim();
  if (!v) return fallback;
  if (v.startsWith("data:") || v.startsWith("blob:")) return v;
  if (v.startsWith("/") && !v.startsWith("//")) return v;
  return fallback;
}

/** 该值是否属于内置预设（用于判断"自定义头像"分支） */
export function isPresetAvatar(value?: string | null): boolean {
  return !!value && AVATAR_PRESETS.includes(value.trim());
}
