/** 配置官网静态构建，按各端正式发布生成下载直链。 */
import { defineConfig } from 'vite'
import { tanstackStart } from '@tanstack/solid-start/plugin/vite'
import solid from 'vite-plugin-solid'
import type { Platform, PlatformReleases } from './src/releases'
import { repo } from './src/site'

const token = process.env.GH_TOKEN || process.env.GITHUB_TOKEN

/** 从 GitHub 正式发布中为各平台选取最新可用安装包。 */
async function platformReleasesApi(): Promise<PlatformReleases> {
  const response = await fetch(`https://api.github.com/repos${new URL(repo).pathname}/releases?per_page=100`, {
    headers: { Accept: 'application/vnd.github+json', ...(token && { Authorization: `Bearer ${token}` }) },
  })
  if (!response.ok) throw new Error(`GitHub releases: HTTP ${response.status}`)
  const entries: {
    draft: boolean
    prerelease: boolean
    tag_name: string
    published_at: string
    assets: { name: string; browser_download_url: string }[]
  }[] = await response.json()
  const published = entries.filter((entry) => !entry.draft && !entry.prerelease)
    .sort((left, right) => Date.parse(right.published_at) - Date.parse(left.published_at))
  const formats: Record<Platform, RegExp> = { ios: /\.ipa$/i, android: /\.apk$/i, desktop: /arm64.*\.zip$/i }
  const releases: PlatformReleases = {}
  for (const platform of Object.keys(formats) as Platform[]) {
    for (const entry of published) {
      if (!entry.tag_name.startsWith(`mb-${platform}-v`)) continue
      const prefix = `${repo}/releases/download/${entry.tag_name}/`
      const asset = entry.assets.find((item) => formats[platform].test(item.name) && item.browser_download_url.startsWith(prefix))
      if (!asset) continue
      releases[platform] = {
        tag: entry.tag_name,
        filename: asset.name,
        download: asset.browser_download_url,
        notes: `${repo}/releases/tag/${entry.tag_name}`,
      }
      break
    }
  }
  return releases
}

export default defineConfig(async () => ({
  define: {
    __RELEASES__: JSON.stringify(await platformReleasesApi()),
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
