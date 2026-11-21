/** 定义官网框架的属性。 */
import type { JSX } from 'solid-js'

/** 导航需要的语言切换目标。 */
export interface IHeaderProps {
  /** 覆盖语言菜单的当前路径，用于 404 等特殊页面。 */
  langPath?: string
}

/** 官网公共框架的输入。 */
export interface ILayoutProps extends IHeaderProps {
  /** 页面主体内容。 */
  children: JSX.Element
}
