/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** 部署级默认租户编码，界面永不展示（plan D4） */
  readonly VITE_DEFAULT_TENANT?: string;
  /** API 基地址，开发期留空走 Vite 代理 */
  readonly VITE_API_BASE?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
