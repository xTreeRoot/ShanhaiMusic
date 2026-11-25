/** 配置官网静态构建，从 GitHub 的最新四端正式发布生成下载直链。 */
import { createHash } from 'node:crypto'
import { defineConfig } from 'vite'
import { tanstackStart } from '@tanstack/solid-start/plugin/vite'
import solid from 'vite-plugin-solid'
import type { ICombinedRelease, IPlatformRelease, Platform } from './src/releases'
import { repo } from './src/site'

const token = process.env.GH_TOKEN || process.env.GITHUB_TOKEN

/** GitHub Release API 中的附件字段。 */
interface IReleaseAsset {
  /** GitHub 保存的文件名。 */
  name: string
  /** 公开下载直链。 */
  browser_download_url: string
  /** 附件上传状态。 */
  state: string
  /** 附件大小，单位为字节。 */
  size: number
  /** GitHub 生成的带 sha256 前缀的摘要。 */
  digest: string
}

/** 正式发布清单中官网使用的安装包信息。 */
interface IManifestPackage {
  /** 展示给用户的平台版本。 */
  version: string
  /** 当前发布下的安装包文件名。 */
  asset: string
  /** 附件大小，单位为字节。 */
  size: number
  /** 安装包的 SHA-256 十六进制摘要。 */
  sha256: string
}

/** 读取最新版发布及清单，核对四个安装包属于同一条 Release。 */
export async function combinedReleaseApi(): Promise<ICombinedRelease> {
  const response = await fetch(`https://api.github.com/repos${new URL(repo).pathname}/releases/latest`, {
    headers: { Accept: 'application/vnd.github+json', ...(token && { Authorization: `Bearer ${token}` }) },
    signal: AbortSignal.timeout(15_000),
  })
  if (!response.ok) throw new Error(`GitHub Latest: HTTP ${response.status}`)
  const entry: {
    name: string
    draft: boolean
    prerelease: boolean
    tag_name: string
    html_url: string
    assets: IReleaseAsset[]
  } = await response.json()
  const tag = entry.tag_name
  const notes = `${repo}/releases/tag/${tag}`
  if (entry.draft || entry.prerelease || !/^mb-release-\d{4}\.\d{2}\.\d{2}(?:\.\d+)?$/.test(tag) || entry.html_url !== notes) {
    throw new Error(`GitHub Latest must point to a published Shanhai release: ${tag}`)
  }
  const downloadBase = `${repo}/releases/download/${tag}/`
  const manifestAsset = entry.assets.find((asset) => asset.name === 'release-manifest.json')
  if (manifestAsset?.state !== 'uploaded' || manifestAsset.browser_download_url !== `${downloadBase}release-manifest.json`) {
    throw new Error(`Missing release manifest: ${tag}`)
  }
  const manifestResponse = await fetch(manifestAsset.browser_download_url, { signal: AbortSignal.timeout(15_000) })
  if (!manifestResponse.ok) throw new Error(`Release manifest: HTTP ${manifestResponse.status}`)
  const manifestText = await manifestResponse.text()
  if (`sha256:${createHash('sha256').update(manifestText).digest('hex')}` !== manifestAsset.digest) {
    throw new Error(`Release manifest SHA-256 mismatch: ${tag}`)
  }
  const manifest: { schema: number; tag: string; platforms: Partial<Record<Platform, IManifestPackage>> } = JSON.parse(manifestText)
  if (manifest.schema !== 1 || manifest.tag !== tag || !manifest.platforms) {
    throw new Error(`Invalid release manifest: ${tag}`)
  }

  /** 校验平台清单和 GitHub 附件元数据后生成下载卡片的数据。 */
  function installer(platform: Platform, format: RegExp): IPlatformRelease {
    const item = manifest.platforms[platform]
    const asset = entry.assets.find((candidate) => candidate.name === item?.asset)
    if (!item || !item.version?.trim() || !/^[\w.-]+$/.test(item.asset) || !format.test(item.asset)
      || !/^[a-f0-9]{64}$/.test(item.sha256) || !Number.isSafeInteger(item.size) || item.size <= 0
      || asset?.state !== 'uploaded' || asset.browser_download_url !== `${downloadBase}${item.asset}`
      || asset.size !== item.size || asset.digest !== `sha256:${item.sha256}`) {
      throw new Error(`Invalid ${platform} installer: ${tag}`)
    }
    return { filename: asset.name, version: item.version, download: asset.browser_download_url }
  }

  return {
    title: entry.name,
    notes,
    platforms: {
      ios: installer('ios', /-unsigned\.ipa$/),
      android: installer('android', /\.apk$/),
      desktop: installer('desktop', /arm64.*\.zip$/i),
      server: installer('server', /linux-amd64\.tar\.gz$/),
    },
  }
}

export default defineConfig(async () => ({
  define: {
    __RELEASE__: JSON.stringify(await combinedReleaseApi()),
  },
  plugins: [
    tanstackStart({
      prerender: {
        enabled: true,
        crawlLinks: true,
        failOnError: true,
        concurrency: 4,
        retryCount: 3,
        retryDelay: 1000,
        filter: ({ path }) => !path.includes('#'),
      },
      pages: [{ path: '/404', prerender: { autoSubfolderIndex: false } }],
    }),
    solid({ ssr: true }),
  ],
}))
