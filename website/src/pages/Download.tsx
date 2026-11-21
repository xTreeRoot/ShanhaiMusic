/** 优先提供 iPhone、iPad 和 Android 的 GitHub 正式安装包。 */
import { For, Show } from 'solid-js'
import { Layout } from '../components/Layout'
import { Icon } from '../components/Icon'
import { useT } from '../i18n'
import { releases, type Platform } from '../releases'
import { repo } from '../site'

const platforms = [
  { id: 'ios', name: 'iPhone / iPad', minimum: 'iOS / iPadOS 17+', format: 'IPA' },
  { id: 'android', name: 'Android', minimum: 'Android 8+', format: 'APK' },
  { id: 'desktop', name: 'macOS', minimum: 'Apple Silicon', format: 'ZIP' },
] as const satisfies ReadonlyArray<{ id: Platform; name: string; minimum: string; format: string }>

export function Download() {
  const t = useT()
  return (
    <Layout>
      <section class="wrap page-section">
        <div class="page-intro">
          <p class="eyebrow">{t('OFFICIAL RELEASES', '正式发布')}</p>
          <h1 class="display">{t('Get Shanhai', '下载山海')}</h1>
          <p class="lede">{t('Choose the official package for your device.', '选你的设备，下载对应的正式安装包。')}</p>
        </div>
        <div class="download-grid">
          <For each={platforms}>
            {(platform) => (
              <article class="card" classList={{ 'desktop-card': platform.id === 'desktop' }}>
                <p class="eyebrow">{platform.minimum}</p>
                <h2>{platform.name}</h2>
                <p>{platform.id === 'ios'
                  ? t('Unsigned IPA. It must be signed before installing; this is not an App Store download.', '未签名 IPA，需要自行签名后安装；不是 App Store 安装包。')
                  : platform.id === 'android'
                    ? t('Download the APK. Use the same app signature to update without uninstalling.', '下载 APK 安装。升级时使用签名一致的安装包覆盖，不用卸载。')
                    : t('Unzip the archive and open the macOS app.', '解压 ZIP，打开「山海.app」。')}</p>
                <Show when={releases[platform.id]} fallback={<a class="text-link" href={`${repo}/releases`}>{t('All releases', '查看全部发布')}</a>}>
                  {(release) => (
                    <div class="download-actions">
                      <a class="btn" classList={{ 'btn-primary': platform.id !== 'desktop', 'btn-ghost': platform.id === 'desktop' }} href={release().download} title={release().filename}>
                        <Icon name="download" size={17} />{t('Download', '下载')} {platform.format}
                      </a>
                      <a class="text-link" href={release().notes}>{release().tag}</a>
                    </div>
                  )}
                </Show>
              </article>
            )}
          </For>
        </div>
        <a class="text-link" href={`${repo}/releases`}>{t('View all releases on GitHub', '在 GitHub 查看全部发布')} <Icon name="arrow" size={16} /></a>
      </section>
    </Layout>
  )
}
