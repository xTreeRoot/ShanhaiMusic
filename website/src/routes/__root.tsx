/** 定义官网页面框架、静态元数据和语言及主题初始化。 */
import { HeadContent, Scripts, createRootRoute } from '@tanstack/solid-router'
import { HydrationScript } from 'solid-js/web'
import { Suspense, type JSX } from 'solid-js'
import { NotFound } from '../components/NotFound'
import { themeScript } from '../theme'
import { langScript, useLang } from '../i18n'
import css from '../styles.css?url'

export const Route = createRootRoute({
  head: () => ({
    meta: [
      { charSet: 'utf-8' },
      { name: 'viewport', content: 'width=device-width, initial-scale=1, viewport-fit=cover' },
      { name: 'theme-color', content: '#f7f7f0', media: '(prefers-color-scheme: light)' },
      { name: 'theme-color', content: '#101b17', media: '(prefers-color-scheme: dark)' },
      { property: 'og:type', content: 'website' },
      { property: 'og:image', content: '/icon.png' },
      { name: 'twitter:card', content: 'summary' },
    ],
    links: [
      { rel: 'icon', href: '/icon.png', type: 'image/png' },
      { rel: 'apple-touch-icon', href: '/icon.png' },
      { rel: 'stylesheet', href: css },
    ],
  }),
  shellComponent: RootDocument,
  notFoundComponent: NotFound,
})

function RootDocument(props: { children: JSX.Element }) {
  const lang = useLang()
  return (
    <html lang={lang() === 'zh' ? 'zh-CN' : 'en'}>
      <head>
        <script innerHTML={langScript} />
        <script innerHTML={themeScript} />
        <HydrationScript />
      </head>
      <body>
        <HeadContent />
        <Suspense>{props.children}</Suspense>
        <Scripts />
      </body>
    </html>
  )
}
