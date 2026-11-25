/** 展示同一条正式发布中的三端客户端和自托管服务端下载。 */
import { For } from 'solid-js'
import { Layout } from '../components/Layout'
import { Icon } from '../components/Icon'
import { useT } from '../i18n'
import { release, type Platform } from '../releases'
import { repo } from '../site'

const platforms = [
  {
    id: 'ios', name: 'iPhone / iPad', minimum: 'iOS / iPadOS 17+', format: 'IPA', primary: true,
    description: ['Sign the IPA with your own certificate before installing.', 'IPA 未签名，请自行签名后安装。'],
  },
  {
    id: 'android', name: 'Android', minimum: 'Android 8+', format: 'APK', primary: true,
    description: ['Use an APK with the same signature to update in place.', '升级时使用签名一致的 APK 覆盖安装。'],
  },
  {
    id: 'desktop', name: 'macOS', minimum: 'Apple Silicon', format: 'ZIP', primary: false,
    description: ['Unzip the archive and open the macOS app.', '解压 ZIP，打开「山海.app」。'],
  },
  {
    id: 'server', name: 'Server', minimum: 'Linux x86-64', format: 'TAR.GZ', primary: false,
    description: ['Run your own Shanhai node for device sync and backups.', '自托管山海节点，用于设备同步和备份。'],
  },
] as const satisfies readonly {
  id: Platform
  name: string
  minimum: string
  format: string
  primary: boolean
  description: readonly [string, string]
}[]

export function Download() {
  const t = useT()
  return (
    <Layout>
      <section class="wrap page-section">
        <div class="page-intro">
          <h1 class="display">{t('Get Shanhai', '下载山海')}</h1>
          <a class="text-link" href={release.notes}>{release.title} <Icon name="arrow" size={16} /></a>
        </div>
        <div class="download-grid">
          <For each={platforms}>
            {(platform) => {
              const installer = release.platforms[platform.id]
              return (
                <article class="card">
                  <p class="eyebrow">{platform.minimum}</p>
                  <h2>{platform.name}</h2>
                  <p>{t(platform.description[0], platform.description[1])}</p>
                  <div class="download-actions">
                    <a class="btn" classList={{ 'btn-primary': platform.primary, 'btn-ghost': !platform.primary }} href={installer.download} title={installer.filename}>
                      <Icon name="download" size={17} />{t('Download', '下载')} {platform.format}
                    </a>
                    <span>{installer.version}</span>
                  </div>
                </article>
              )
            }}
          </For>
        </div>
        <a class="text-link" href={`${repo}/releases`}>{t('View all releases on GitHub', '在 GitHub 查看全部发布')} <Icon name="arrow" size={16} /></a>
      </section>
    </Layout>
  )
}
