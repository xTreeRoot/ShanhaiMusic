/** 展示山海的移动端体验与桌面补充入口。 */
import { Layout } from '../components/Layout'
import { Icon } from '../components/Icon'
import { LangLink, useT } from '../i18n'

export function Home() {
  const t = useT()
  return (
    <Layout>
      <section class="hero wrap">
        <div class="hero-copy">
          <p class="eyebrow">{t('NATIVE APPS · iOS / ANDROID', '移动原生 · iOS / Android')}</p>
          <h1><span>{t('Shanhai', '山海')}</span><small>{t('Every platform, one place.', '万端归一')}</small></h1>
          <p class="lede">{t(
            'Search across plugins, switch between music and video, and keep what you find in your own library.',
            '跨插件搜索歌单和视频，随时切换播放模式，把喜欢的内容收进自己的资料库。',
          )}</p>
          <div class="actions">
            <LangLink to="/download" class="btn btn-primary">
              <Icon name="download" size={18} />{t('Get Shanhai', '下载山海')}
            </LangLink>
            <LangLink to="/plugins" class="btn btn-ghost">
              {t('Plugin library', '浏览插件库')}<Icon name="arrow" size={17} />
            </LangLink>
          </div>
        </div>
        <div class="mobile-preview">
          <figure class="phone-preview">
            <img src="/screenshots/ios.webp" width="540" height="1115" alt={t('Shanhai music player on iPhone', '山海 iPhone 客户端的音乐播放页')} />
            <figcaption>iPhone</figcaption>
          </figure>
          <figure class="phone-preview">
            <img src="/screenshots/android.webp" width="540" height="1140" alt={t('Shanhai search on Android', '山海 Android 客户端的跨插件搜索页')} />
            <figcaption>Android</figcaption>
          </figure>
        </div>
      </section>
      <section class="desktop-preview wrap" aria-labelledby="desktop-title">
        <div>
          <p class="eyebrow">macOS</p>
          <h2 id="desktop-title">{t('Also on desktop', '也可以在桌面使用')}</h2>
          <p class="lede">{t('A native macOS companion for your music, videos and plugins.', '桌面端同样支持外部插件、播放和资料库。')}</p>
          <LangLink class="text-link" to="/download">{t('View desktop downloads', '查看桌面下载')} <Icon name="arrow" size={16} /></LangLink>
        </div>
        <img src="/screenshots/desktop.webp" width="1180" height="900" alt={t('Shanhai desktop discovery view', '山海桌面客户端的发现页')} loading="lazy" />
      </section>
    </Layout>
  )
}
