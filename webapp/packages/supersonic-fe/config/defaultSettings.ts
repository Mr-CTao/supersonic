/**
 * 应用默认布局配置。
 *
 * 职责：统一顶部导航、内容宽度、主题色和侧边栏行为；顶部导航使用 ProLayout 内置固定模式，
 * 页面内容超过视口时仅滚动内容区域，避免导航随页面离开可视区。
 */
import type { ProLayoutProps } from '@ant-design/pro-components';
export type DefaultSetting = ProLayoutProps & {
  pwa?: boolean;
  logo?: string;
};
const Settings: DefaultSetting = {
  navTheme: 'light',
  colorPrimary: '#296DF3',
  layout: 'top',
  contentWidth: 'Fluid',
  fixedHeader: true,
  fixSiderbar: true,
  colorWeak: false,
  title: '',
  pwa: false,
  iconfontUrl: '//at.alicdn.com/t/c/font_4120566_x5c4www9bqm.js',
  // splitMenus: true,
  // menu: {
  //   autoClose: false,
  //   ignoreFlatMenu: true,
  // },
};
export const publicPath = '/webapp/';
export const basePath = '/webapp/';

export default Settings;
