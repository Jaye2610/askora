// Askora 像素头像库
//
// 设计原则
//   1. 像素画在 SVG 里就是一堆 <rect>，本质是**数据**。所以这里不是"画图"，
//      而是「字符网格 + 调色板 → data-URI」的生成器。
//   2. 衣服色是**生成时传入的参数**，不是另画一张图。同一个物种 × N 种衣服色
//      = N 个头像，且衣服色只有一处定义（CLOTHING）。
//   3. 像素是**可精确寻址**的：说"第 6 行第 9 列"就能定位到某个像素，
//      所以迭代远比"再调调"精确。
//
// 网格字符表
//   .  透明
//   k  描边（深色）
//   b  体色（物种主色）
//   f  特征色（耳朵内侧 / 尾巴 / 口鼻，用来区分物种）
//   w  眼白 / 高光
//   y  深色（瞳孔 / 鼻头 / 嘴）
//   c  衣服色  ← 参数化
//   g  衣服暗部（由衣服色自动算，不用手配）

export const PIXEL_SIZE = 16;

type Grid = string[];

/** 衣服色：新增一种颜色只需在这里加一行 */
export const CLOTHING = {
   蓝: "#3b82f6",
   紫: "#8b5cf6",
   靛: "#6366f1",
   青: "#06b6d4",
   绿: "#10b981",
   橙: "#f59e0b",
   玫: "#ec4899",
   红: "#ef4444",
   石墨: "#475569"
} as const;

export type ClothingName = keyof typeof CLOTHING;

/** 物种：body / feature / outline 三色决定它像什么 */
const SPECIES = {
  robot: { body: "#cbd5e1", feature: "#94a3b8", outline: "#334155", label: "机器人" },
  cat: { body: "#f5c98a", feature: "#f0a860", outline: "#7c4a1e", label: "猫" },
  rabbit: { body: "#f7f3ee", feature: "#f4b6c2", outline: "#8a7f78", label: "兔" },
  bear: { body: "#c99a6b", feature: "#e8c9a0", outline: "#6b4527", label: "熊" },
  fox: { body: "#f08a4b", feature: "#fbd9c0", outline: "#8f4a1c", label: "狐" },
  panda: { body: "#f8fafc", feature: "#334155", outline: "#1e293b", label: "熊猫" }
} as const;

export type SpeciesName = keyof typeof SPECIES;

// ── 网格 ────────────────────────────────────────────────────────────────
// 16×16。头部占 2–11 行（耳朵 / 眼睛 / 口鼻），身体占 12–15 行（衣服色）。

const GRIDS: Record<SpeciesName, Grid> = {
  // 机器人：天线 + 方头 + 两只方眼 + 格栅嘴。管理员默认头像
  robot: [
    "................",
    ".......kk.......",
    ".......kk.......",
    "....kkkkkkkk....",
    "...kbbbbbbbbk...",
    "...kbbbbbbbbk...",
    "...kbwwbbwwbk...",
    "...kbwybbywbk...",
    "...kbbbbbbbbk...",
    "...kbkkkkkkbk...",
    "....kkkkkkkk....",
    "...kcccccccck...",
    "..kcccccccccck..",
    "..kckcccccckck..",
    "..kkkkkkkkkkkk..",
    "................"
  ],
  // 猫：两枚尖耳是区分度最高的特征
  cat: [
    "................",
    "..kk........kk..",
    ".kffk......kffk.",
    ".kfbbk....kbbfk.",
    ".kbbbbkkkkbbbbk.",
    ".kbbbbbbbbbbbbk.",
    ".kbwwbbbbbbwwbk.",
    ".kbwybbbbbbywbk.",
    ".kbbbbbbbbbbbbk.",
    ".kbbbkkffkkbbbk.",
    "..kbbbbbbbbbbk..",
    "...kkkkkkkkkk...",
    "..kcccccccccck..",
    "..kcccccccccck..",
    "..kkkkkkkkkkkk..",
    "................"
  ],
  // 兔：长耳向上（与猫的尖耳方向不同）
  rabbit: [
    "....kk....kk....",
    "...kffk..kffk...",
    "...kffk..kffk...",
    "....kkk..kkk....",
    "...kbbbbbbbbk...",
    "..kbbbbbbbbbbk..",
    "..kbwwbbbbwwbk..",
    "..kbwybbbbbywbk.",
    "..kbbbbbbbbbbk..",
    "..kbbbbbffbbbk..",
    "...kbbbbbbbbk...",
    "....kkkkkkkk....",
    "..kcccccccccck..",
    "..kcccccccccck..",
    "..kkkkkkkkkkkk..",
    "................"
  ],
  // 熊：圆耳（无尖角）+ 大面积浅色口鼻
  bear: [
    "................",
    "...kkk....kkk...",
    "..kfffk..kfffk..",
    "..kffffkkffffk..",
    "..kbbbbbbbbbbk..",
    ".kbbbbbbbbbbbbk.",
    ".kbwwbbbbbbwwbk.",
    ".kbwybbbbbbywbk.",
    ".kbbbbbbbbbbbbk.",
    ".kbbffffffffbbk.",
    "..kbfffyyfffbbk.",
    "...kkkkkkkkkk...",
    "..kcccccccccck..",
    "..kcccccccccck..",
    "..kkkkkkkkkkkk..",
    "................"
  ],
  // 狐：宽三角耳 + 白口鼻
  fox: [
    "................",
    ".kk..........kk.",
    "kffk........kffk",
    "kfbbk......kbbfk",
    "kbbbbkkkkkkbbbbk",
    ".kbbbbbbbbbbbbk.",
    ".kbwwbbbbbbwwbk.",
    ".kbwybbbbbbbywbk",
    ".kbbbbbbbbbbbbk.",
    ".kbbbbffffbbbbk.",
    "..kbbffyyffbbk..",
    "...kkkkkkkkkk...",
    "..kcccccccccck..",
    "..kcccccccccck..",
    "..kkkkkkkkkkkk..",
    "................"
  ],
  // 熊猫：黑耳 + 黑眼圈（feature 就是深色）
  panda: [
    "................",
    "...kkk....kkk...",
    "..kfffk..kfffk..",
    "..kffffkkffffk..",
    "..kbbbbbbbbbbk..",
    ".kbbbbbbbbbbbbk.",
    ".kbffbbbbbbffbk.",
    ".kfwyffffbywfk..",
    ".kbffbbbbbbffbk.",
    ".kbbbbffffbbbbk.",
    "..kbbbbyybbbbk..",
    "...kkkkkkkkkk...",
    "..kcccccccccck..",
    "..kcccccccccck..",
    "..kkkkkkkkkkkk..",
    "................"
  ]
};

// ── 生成 ────────────────────────────────────────────────────────────────

/** 把 hex 压暗，用于衣服暗部，避免每件衣服都手配两个色 */
function darken(hex: string, ratio = 0.78): string {
  const n = parseInt(hex.slice(1), 16);
  const ch = [(n >> 16) & 255, (n >> 8) & 255, n & 255].map((v) =>
    Math.max(0, Math.round(v * ratio))
  );
  return "#" + ch.map((v) => v.toString(16).padStart(2, "0")).join("");
}

/**
 * 生成一个像素头像的 data-URI。
 * 不依赖外网、不依赖字体 —— 整张图就是纯 <rect>。
 */
export function pixelAvatar(species: SpeciesName, clothing: ClothingName): string {
  const sp = SPECIES[species];
  const cloth = CLOTHING[clothing];
  const PAL: Record<string, string> = {
    k: sp.outline,
    b: sp.body,
    f: sp.feature,
    w: "#ffffff",
    y: sp.outline,
    c: cloth,
    g: darken(cloth)
  };
  const grid = GRIDS[species];
  const rects: string[] = [];
  for (let row = 0; row < PIXEL_SIZE; row++) {
    for (let col = 0; col < PIXEL_SIZE; col++) {
      const ch = grid[row]?.[col] ?? ".";
      const fill = PAL[ch];
      if (!fill) continue;
      rects.push(`<rect x="${col}" y="${row}" width="1" height="1" fill="${fill}"/>`);
    }
  }
  const svg =
    `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${PIXEL_SIZE} ${PIXEL_SIZE}" ` +
    `shape-rendering="crispEdges">${rects.join("")}</svg>`;
  return `data:image/svg+xml;utf8,${encodeURIComponent(svg)}`;
}

/** 物种列表（给 UI 分组用） */
export const SPECIES_LIST = Object.entries(SPECIES).map(([key, v]) => ({
  key: key as SpeciesName,
  label: v.label
}));

/** 衣服色列表（给 UI 分组用） */
export const CLOTHING_LIST = Object.entries(CLOTHING).map(([key, v]) => ({
  key: key as ClothingName,
  label: key,
  color: v
}));

/**
 * 预设头像库（唯一来源）。
 * 存储时把选中的 data-URI 写进 user.avatar。
 */
export const AVATAR_PRESETS: string[] = SPECIES_LIST.flatMap((s) =>
  CLOTHING_LIST.map((c) => pixelAvatar(s.key, c.key))
);

/** 管理员默认头像：机器人 + 靛（与后台主色一致） */
export const DEFAULT_ADMIN_AVATAR = pixelAvatar("robot", "靛");
