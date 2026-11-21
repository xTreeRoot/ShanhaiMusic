/** 显示双语 404 页面和官网首页入口。 */
import { Link } from '@tanstack/solid-router'
import { createSignal, onMount } from 'solid-js'
import { Layout } from './Layout'
import { Icon } from './Icon'

export function NotFound() {
  const [zh, setZh] = createSignal(false)
  onMount(() => {
    const p = location.pathname
    let chosen: string | null = null
    try {
      chosen = localStorage.getItem('lang')
    } catch {}
    setZh(p === '/zh' || p.startsWith('/zh/') || chosen === 'zh' || (!chosen && /^zh\b/i.test(navigator.language)))
  })
  const t = (en: string, cn: string) => (zh() ? cn : en)
  return (
    <Layout langPath="/">
      <section class="lost">
        <p class="eyebrow">404</p>
        <h1 class="display">{t('Page not found', '找不到这个页面')}</h1>
        <Link to={zh() ? ('/zh' as '/') : '/'} class="btn btn-primary">
          {t('Back Home', '回到首页')}
          <Icon name="arrow" size={16} />
        </Link>
      </section>
    </Layout>
  )
}
