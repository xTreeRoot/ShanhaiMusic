/** 展示山海维护的插件，不读取上游的通用插件列表。 */
import { For } from 'solid-js'
import { Layout } from '../components/Layout'
import { GitHubMark, Icon } from '../components/Icon'
import { useT } from '../i18n'

const catalog = 'https://github.com/stars/xTreeRoot/lists/fork-mygo-%E5%B1%B1%E6%B5%B7-%E8%87%AA%E8%A1%8C%E7%BB%B4%E6%8A%A4%E4%BB%93%E5%BA%93-%E5%A4%9A%E5%B9%B3%E5%8F%B0'

const plugins = [
  { repo: 'mygo-music-bilibili', name: ['Bilibili', '哔哩哔哩'], detail: ['Video and search', '视频与搜索'] },
  { repo: 'mygo-music-netease', name: ['NetEase Cloud Music', '网易云音乐'], detail: ['Music and playlists', '音乐与歌单'] },
  { repo: 'mygo-music-qqmusic', name: ['QQ Music', 'QQ 音乐'], detail: ['Music, playlists and lyrics', '音乐、歌单与歌词'] },
  { repo: 'mygo-music-bodian', name: ['Bodian Music', '波点音乐'], detail: ['Music and radio', '音乐与电台'] },
  { repo: 'mygo-music-soda', name: ['Soda Music', '汽水音乐'], detail: ['Music plugin', '音乐插件'] },
  { repo: 'mygo-music-youtube-music', name: ['YouTube Music', 'YouTube Music'], detail: ['Music plugin', '音乐插件'] },
  { repo: 'mygo-ximalaya', name: ['Ximalaya', '喜马拉雅'], detail: ['Audio, subscriptions and liked sounds', '声音、订阅与喜欢列表'] },
] as const

export function Plugins() {
  const t = useT()
  return (
    <Layout>
      <section class="wrap page-section">
        <div class="page-intro">
          <p class="eyebrow">{t('SHANHAI PLUGINS', '山海维护')}</p>
          <h1 class="display">{t('Plugin library', '山海插件库')}</h1>
          <p class="lede">{t('Install from a repository or download its plugin ZIP and import it in Shanhai.', '可以跳转山海安装，也可以下载插件发布页的 ZIP，从山海内导入。')}</p>
          <a class="btn btn-ghost" href={catalog}><GitHubMark size={17} />{t('View our GitHub Stars list', '打开我们的 GitHub 星标列表')}</a>
        </div>
        <div class="plugin-grid">
          <For each={plugins}>
            {(plugin) => (
              <article class="card plugin-card">
                <h2>{t(plugin.name[0], plugin.name[1])}</h2>
                <p>{t(plugin.detail[0], plugin.detail[1])}</p>
                <div class="download-actions">
                  <a class="text-link" href={`mb://install-plugin?repo=xTreeRoot/${plugin.repo}`}>
                    <Icon name="download" size={16} />{t('Open in Shanhai', '在山海安装')}
                  </a>
                  <a class="text-link" href={`https://github.com/xTreeRoot/${plugin.repo}/releases`}>
                    <GitHubMark size={16} />{t('Releases', '发布页')}
                  </a>
                </div>
              </article>
            )}
          </For>
        </div>
      </section>
    </Layout>
  )
}
