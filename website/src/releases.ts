/** 定义供构建和下载页共用的正式发布数据。 */

/** 官网展示的应用平台。 */
export type Platform = 'ios' | 'android' | 'desktop'

/** 一个平台的正式安装包。 */
export interface IPlatformRelease {
  /** GitHub 发布标签。 */
  tag: string
  /** 安装包文件名。 */
  filename: string
  /** 官方安装包直链。 */
  download: string
  /** GitHub 发布说明地址。 */
  notes: string
}

/** 按平台单独选择的正式发布。 */
export type PlatformReleases = Partial<Record<Platform, IPlatformRelease>>

declare const __RELEASES__: PlatformReleases

export const releases = __RELEASES__
