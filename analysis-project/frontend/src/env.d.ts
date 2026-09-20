/// <reference types="vite/client" />

declare module '*.vue' {
  import type { DefineComponent } from 'vue';
  const component: DefineComponent<{}, {}, any>;
  export default component;
}

// element-plus 语言包为 .mjs 文件, 无官方类型声明
declare module 'element-plus/dist/locale/*.mjs' {
  import type { Language } from 'element-plus/es/locale';
  const lang: Language;
  export default lang;
}
