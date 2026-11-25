/** 定义官网下载页使用的四端正式发布数据。 */

/** 官网展示的应用平台。 */
export type Platform = 'ios' | 'android' | 'desktop' | 'server'

/** 一个平台的正式安装包。 */
export interface IPlatformRelease {
  /** 此平台的版本号，iOS 同时包含构建号。 */
  version: string
  /** 安装包文件名。 */
  filename: string
  /** 官方安装包直链。 */
  download: string
}

/** 一条包含三端客户端和自托管服务端的 GitHub 正式发布。 */
export interface ICombinedRelease {
  /** GitHub 上展示的发布标题。 */
  title: string
  /** GitHub 发布说明地址。 */
  notes: string
  /** 同一条发布中的四个安装包。 */
  platforms: Record<Platform, IPlatformRelease>
}

declare const __RELEASE__: ICombinedRelease

export const release = __RELEASE__
