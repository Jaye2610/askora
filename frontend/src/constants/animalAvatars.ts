// Askora 头像库 —— 扁平矢量插画
//
// 为什么不用像素画：像素图必须在整数倍缩放下渲染才不会糊（36px 容器 ÷ 16 像素
// = 2.25 倍），换个容器尺寸就要重新对齐。矢量图不存在这个问题 —— 任意尺寸都清晰，
// 所以容器怎么改都不用管。
//
// 做法：每个物种 = 一组几何描述（耳朵形状 + 特征色 + 面部细节），
// 由同一段代码拼出 SVG。**衣服色是生成参数**，不是另画一张图。
//
// 物种区分靠三件事，从远到近：
//   1. 耳朵轮廓（尖 / 长 / 圆 / 宽三角）—— 剪影层面就能分辨
//   2. 特征色（耳内、口鼻区）
//   3. 面部细节（熊猫的黑眼圈、狐的白口鼻）

export const AVATAR_SIZE = 48;

/** 衣服色：新增一种只需在这里加一行 */
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

type EarKind = "pointed" | "long" | "round" | "wide" | "flat";

interface SpeciesSpec {
  label: string;
  /** 主体毛色 */
  fur: string;
  /** 耳内 / 阴影 */
  inner: string;
  /** 口鼻区 */
  muzzle: string;
  /** 鼻头 */
  nose: string;
  ear: EarKind;
  /** 黑眼圈（熊猫） */
  eyePatch?: string;
  /** 面部斑纹（熊猫的耳朵用） */
  earFill?: string;
  /** 腮红。不填则不加 —— 机器人不该有腮红 */
  blush?: string;
  /** 胡须（只有猫有，其它物种加了会变猫） */
  whiskers?: boolean;
  /** 耳内绒毛线（猫 / 狐） */
  earTuft?: boolean;
}

const SPECIES: Record<string, SpeciesSpec> = {
  robot: {
    label: "机器人",
    fur: "#cbd5e1",
    inner: "#94a3b8",
    muzzle: "#e2e8f0",
    nose: "#334155",
    ear: "flat"
  },
  cat: {
    label: "猫",
    fur: "#f5c98a",
    inner: "#f0a860",
    muzzle: "#fdf3e3",
    nose: "#c2703a",
    ear: "pointed",
    blush: "#f7b08e",
    whiskers: true,
    earTuft: true
  },
  rabbit: {
    label: "兔",
    fur: "#f7f3ee",
    inner: "#f4b6c2",
    muzzle: "#ffffff",
    nose: "#e58fa2",
    ear: "long",
    blush: "#f6bfc9"
  },
  bear: {
    label: "熊",
    fur: "#c99a6b",
    inner: "#e8c9a0",
    muzzle: "#f0dcc4",
    nose: "#5c3a20",
    ear: "round",
    blush: "#e0a878"
  },
  fox: {
    label: "狐",
    fur: "#f08a4b",
    inner: "#fbd9c0",
    muzzle: "#fdeee2",
    nose: "#8f4a1c",
    ear: "wide",
    blush: "#f7a87c",
    earTuft: true
  },
  panda: {
    label: "熊猫",
    fur: "#f8fafc",
    inner: "#334155",
    muzzle: "#ffffff",
    nose: "#1e293b",
    ear: "round",
    earFill: "#334155",
    eyePatch: "#334155",
    blush: "#e6d3d6"
  }
};

export type SpeciesName = keyof typeof SPECIES;

// 头部与身体的关键几何（48 × 48 画布）
const HEAD = { cx: 24, cy: 23, r: 11.5 };
const BODY = { x: 10.5, y: 32, w: 27, h: 20, rx: 9.5 };

/** 耳朵：以头部中心为基准生成路径 / 图元。全部在头部之后绘制（压在头下面） */
function ears(kind: EarKind, sp: SpeciesSpec): string {
  const fill = sp.earFill ?? sp.fur;
  const innerFill = sp.inner;
  switch (kind) {
    // 尖耳：叶形 —— 用二次贝塞尔把三角的尖角磨圆，不再是一刀切
    case "pointed":
      return `
        <path d="M16.0 15.6 Q11.4 9.4 12.6 4.4 Q17.8 7.6 22.4 11.2 Z" fill="${fill}"/>
        <path d="M32.0 15.6 Q36.6 9.4 35.4 4.4 Q30.2 7.6 25.6 11.2 Z" fill="${fill}"/>
        <path d="M16.4 14.2 Q13.6 9.8 14.2 6.6 Q17.8 8.8 20.6 11.2 Z" fill="${innerFill}"/>
        <path d="M31.6 14.2 Q34.4 9.8 33.8 6.6 Q30.2 8.8 27.4 11.2 Z" fill="${innerFill}"/>`;
    // 长耳：竖起的椭圆，略微外倾
    case "long":
      return `
        <ellipse cx="18.4" cy="8.6" rx="3.1" ry="7.6" transform="rotate(-11 18.4 8.6)" fill="${fill}"/>
        <ellipse cx="29.6" cy="8.6" rx="3.1" ry="7.6" transform="rotate(11 29.6 8.6)" fill="${fill}"/>
        <ellipse cx="18.4" cy="9.4" rx="1.6" ry="5.2" transform="rotate(-11 18.4 9.4)" fill="${innerFill}"/>
        <ellipse cx="29.6" cy="9.4" rx="1.6" ry="5.2" transform="rotate(11 29.6 9.4)" fill="${innerFill}"/>`;
    // 圆耳：两个圆，内嵌小圆
    case "round":
      return `
        <circle cx="15.6" cy="13.4" r="5" fill="${fill}"/>
        <circle cx="32.4" cy="13.4" r="5" fill="${fill}"/>
        <circle cx="15.6" cy="13.6" r="2.4" fill="${innerFill}"/>
        <circle cx="32.4" cy="13.6" r="2.4" fill="${innerFill}"/>`;
    // 宽耳：狐 —— 保留“宽”的物种特征，但尖端改圆肩，不再扎人
    case "wide":
      return `
        <path d="M14.2 17.2 Q8.6 11.2 9.4 5.2 Q15.8 8.0 23.0 12.2 Z" fill="${fill}"/>
        <path d="M33.8 17.2 Q39.4 11.2 38.6 5.2 Q32.2 8.0 25.0 12.2 Z" fill="${fill}"/>
        <path d="M15.4 15.8 Q11.6 11.6 12.2 7.6 Q16.8 9.6 21.6 12.6 Z" fill="${innerFill}"/>
        <path d="M32.6 15.8 Q36.4 11.6 35.8 7.6 Q31.2 9.6 26.4 12.6 Z" fill="${innerFill}"/>`;
    // 扁平耳：机器人与人类等无耳造型，换成天线 / 侧方接收器
    default:
      return `
        <rect x="22.6" y="3.4" width="2.8" height="8" rx="1.4" fill="${sp.inner}"/>
        <circle cx="24" cy="3.6" r="2.6" fill="${sp.nose}"/>
        <rect x="6.2" y="19" width="3.6" height="7" rx="1.8" fill="${sp.inner}"/>
        <rect x="38.2" y="19" width="3.6" height="7" rx="1.8" fill="${sp.inner}"/>`;
  }
}

/** 面部：口鼻区 + 眼睛 + 鼻头（压在头部之上） */
function face(sp: SpeciesSpec): string {
  const parts: string[] = [];
  // 黑眼圈（熊猫）
  if (sp.eyePatch) {
    parts.push(`<ellipse cx="18.8" cy="22.2" rx="4.2" ry="4.8" transform="rotate(-16 18.8 22.2)" fill="${sp.eyePatch}"/>`);
    parts.push(`<ellipse cx="29.2" cy="22.2" rx="4.2" ry="4.8" transform="rotate(16 29.2 22.2)" fill="${sp.eyePatch}"/>`);
  }
  // 口鼻区
  parts.push(`<ellipse cx="24" cy="28.2" rx="6.6" ry="5" fill="${sp.muzzle}"/>`);
  // 眼睛
  const eyeFill = sp.eyePatch ? "#ffffff" : "#2c2118";
  parts.push(`<circle cx="19.2" cy="21.8" r="1.85" fill="${eyeFill}"/>`);
  parts.push(`<circle cx="28.8" cy="21.8" r="1.85" fill="${eyeFill}"/>`);
  // 高光
  parts.push(`<circle cx="19.8" cy="21.1" r="0.6" fill="#ffffff" opacity="0.9"/>`);
  parts.push(`<circle cx="29.4" cy="21.1" r="0.6" fill="#ffffff" opacity="0.9"/>`);
  // 鼻头 + 嘴
  parts.push(`<ellipse cx="24" cy="26.6" rx="2.1" ry="1.5" fill="${sp.nose}"/>`);
  parts.push(
    `<path d="M24 28.1 V29.4 M24 29.4 Q22.4 30.9 20.9 29.6 M24 29.4 Q25.6 30.9 27.1 29.6" ` +
      `stroke="${sp.nose}" stroke-width="0.7" fill="none" stroke-linecap="round" opacity="0.85"/>`
  );
  return parts.join("\n        ");
}

/** 细节层：砂红 / 胡须 / 耳内绒毛 / 前爪。压在面部之上 */
function details(sp: SpeciesSpec): string {
  const parts: string[] = [];
  // 砂红（机器人不填 -> 不加）
  if (sp.blush) {
    parts.push(`<ellipse cx="16.0" cy="26.8" rx="3.1" ry="1.8" fill="${sp.blush}" opacity="0.5"/>`);
    parts.push(`<ellipse cx="32.0" cy="26.8" rx="3.1" ry="1.8" fill="${sp.blush}" opacity="0.5"/>`);
  }
  // 胡须：只有猫有。其它物种加上会长得像猫
  if (sp.whiskers) {
    parts.push(
      `<path d="M12.8 26.4 Q9.2 25.4 6.6 25.8 M13.0 28.2 Q9.4 28.2 6.8 29.2" ` +
        `stroke="${sp.inner}" stroke-width="0.55" fill="none" stroke-linecap="round" opacity="0.75"/>`
    );
    parts.push(
      `<path d="M35.2 26.4 Q38.8 25.4 41.4 25.8 M35.0 28.2 Q38.6 28.2 41.2 29.2" ` +
        `stroke="${sp.inner}" stroke-width="0.55" fill="none" stroke-linecap="round" opacity="0.75"/>`
    );
  }
  // 耳内绒毛线（猫 / 狐）
  if (sp.earTuft) {
    parts.push(
      `<path d="M15.2 12.6 Q13.4 8.6 13.8 6.4 M17.2 12.8 Q16.0 9.2 16.4 7.0" ` +
        `stroke="${sp.inner}" stroke-width="0.5" fill="none" stroke-linecap="round" opacity="0.7"/>`
    );
    parts.push(
      `<path d="M32.8 12.6 Q34.6 8.6 34.2 6.4 M30.8 12.8 Q32.0 9.2 31.6 7.0" ` +
        `stroke="${sp.inner}" stroke-width="0.5" fill="none" stroke-linecap="round" opacity="0.7"/>`
    );
  }
  // 前爪（踩在衣服下摆上，溢出画布底部）
  parts.push(`<ellipse cx="17.6" cy="45.6" rx="3.5" ry="2.3" fill="${sp.fur}"/>`);
  parts.push(`<ellipse cx="30.4" cy="45.6" rx="3.5" ry="2.3" fill="${sp.fur}"/>`);
  return parts.join("\n        ");
}

function darken(hex: string, ratio = 0.82): string {
  const n = parseInt(hex.slice(1), 16);
  const ch = [(n >> 16) & 255, (n >> 8) & 255, n & 255].map((v) => Math.max(0, Math.round(v * ratio)));
  return "#" + ch.map((v) => v.toString(16).padStart(2, "0")).join("");
}

/**
 * 生成一个头像的 data-URI。
 * 矢量图，任意尺寸都清晰 —— 不需要任何 image-rendering 处理。
 */
export function animalAvatar(species: SpeciesName, clothing: ClothingName): string {
  const sp = SPECIES[species];
  const cloth = CLOTHING[clothing];
  const svg = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${AVATAR_SIZE} ${AVATAR_SIZE}">
  <g>
    ${ears(sp.ear, sp)}
    <circle cx="${HEAD.cx}" cy="${HEAD.cy}" r="${HEAD.r}" fill="${sp.fur}"/>
    <rect x="${BODY.x}" y="${BODY.y}" width="${BODY.w}" height="${BODY.h}" rx="${BODY.rx}" fill="${cloth}"/>
    <rect x="${BODY.x + 4}" y="${BODY.y}" width="${BODY.w - 8}" height="4.5" rx="2.25" fill="${darken(cloth)}"/>
    ${face(sp)}
    ${details(sp)}
  </g>
</svg>`;
  return `data:image/svg+xml;utf8,${encodeURIComponent(svg.replace(/\s+/g, " ").trim())}`;
}

export const SPECIES_LIST = Object.entries(SPECIES).map(([key, v]) => ({
  key: key as SpeciesName,
  label: v.label
}));

export const CLOTHING_LIST = Object.entries(CLOTHING).map(([key, v]) => ({
  key: key as ClothingName,
  label: key,
  color: v
}));

/** 预设头像库（唯一来源）。存储时把 data-URI 写进 user.avatar */
export const AVATAR_PRESETS: string[] = SPECIES_LIST.flatMap((s) =>
  CLOTHING_LIST.map((c) => animalAvatar(s.key, c.key))
);

/** 管理员默认头像：机器人 + 靛（与后台主色一致） */
export const DEFAULT_ADMIN_AVATAR = animalAvatar("robot", "靛");
