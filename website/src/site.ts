/** 定义山海官网品牌、源码地址和各端发布数据。 */
import type { Lang } from './i18n'

export const repo = 'https://github.com/xTreeRoot/ShanhaiMusic'

export const site = {
  name: '山海',
  title: {
    en: 'Shanhai · Every platform, one place',
    zh: '山海 · 万端归一',
  } satisfies Record<Lang, string>,
  description: {
    en: 'Search, play and organize music and video across plugins. Built for iPhone, iPad and Android, with a macOS companion.',
    zh: '用插件连接音乐和视频，在一个入口搜索、播放和收藏。提供 iPhone、iPad、Android 原生应用，也可在 macOS 使用。',
  } satisfies Record<Lang, string>,
}
