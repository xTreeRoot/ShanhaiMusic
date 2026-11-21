/** 渲染山海官网的品牌、导航和页脚。 */
import type { IHeaderProps, ILayoutProps } from './Layout.types'
import { GitHubMark, Icon } from './Icon'
import { ThemeSwitch } from '../theme'
import { repo, site } from '../site'
import { LangLink, LangMenu, useT } from '../i18n'

// langPath is where the language menu links to, for a page that isn't one
// of the site's own, like the 404.
export function Layout(props: ILayoutProps) {
  return (
    <>
      <Header langPath={props.langPath} />
      <main>{props.children}</main>
      <Footer />
    </>
  )
}

function Header(props: IHeaderProps) {
  const t = useT()
  return (
    <header class="nav">
      <div class="nav-inner">
        <LangLink to="/" class="brand" aria-label={site.name}>
          <img src="/icon.png" alt="" width="32" height="32" />
          <span>{site.name}</span>
        </LangLink>
        <div class="nav-end">
          <LangLink to="/download" class="nav-link" aria-label={t('Download', '下载')}>
            <Icon name="download" size={16} />
            <span>{t('Download', '下载')}</span>
          </LangLink>
          <LangLink to="/plugins" class="nav-link" aria-label={t('Plugins', '插件')}>
            <Icon name="plugin" size={16} />
            <span>{t('Plugins', '插件库')}</span>
          </LangLink>
          <LangMenu path={props.langPath} />
          <ThemeSwitch class="nav-theme" />
          <a class="icon-link" href={repo} aria-label={t('Source code on GitHub', 'GitHub 上的源代码')}>
            <GitHubMark size={19} />
          </a>
        </div>
      </div>
    </header>
  )
}

function Footer() {
  const t = useT()
  return (
    <footer class="footer">
      <div class="footer-inner">
        <span>
          {t('Shanhai · Every platform, one place', '山海 · 万端归一')} · <a href={`${repo}/blob/main/LICENSE`}>AGPL-3.0</a>
        </span>
        <a class="icon-link" href={repo} aria-label={t('Source code on GitHub', 'GitHub 上的源代码')}>
          <GitHubMark size={17} />
        </a>
      </div>
    </footer>
  )
}
