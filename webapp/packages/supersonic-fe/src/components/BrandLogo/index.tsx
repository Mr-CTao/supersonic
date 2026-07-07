/**
 * DC WmsHub 品牌 Logo 组件。
 *
 * 职责：
 * - 以矢量 SVG 方式呈现 DC WmsHub 一体化品牌 Logo；
 * - 复用同一套结构到主框架头部和登录页，避免不同入口 Logo 风格漂移；
 * - 保留图片稿里的定制字形，同时避免 PNG 缩放发虚。
 */
import React from 'react';
import logoSvg from '@/assets/logo-dc-wmshub.svg';
import styles from './style.less';

type BrandLogoSize = 'header' | 'login';

type BrandLogoProps = {
  /** Logo 使用场景；不同场景只调整尺寸，不改变品牌结构。 */
  size?: BrandLogoSize;
  /** 外部样式类名，用于在少数页面里补充布局约束。 */
  className?: string;
};

const SIZE_CLASS_NAME: Record<BrandLogoSize, string> = {
  header: styles.header,
  login: styles.login,
};

/**
 * 渲染 DC WmsHub 品牌 Logo。
 *
 * @param props Logo 的尺寸场景和可选扩展类名。
 * @returns 可直接用于页面标题或主框架头部的 Logo 节点。
 * @throws 当前组件不主动抛出异常。
 * @example
 * ```tsx
 * <BrandLogo size="header" />
 * <BrandLogo size="login" />
 * ```
 */
const BrandLogo: React.FC<BrandLogoProps> = ({ size = 'header', className }) => {
  const logoClassName = [styles.logo, SIZE_CLASS_NAME[size], className].filter(Boolean).join(' ');

  return (
    <img className={logoClassName} src={logoSvg} alt="DC WmsHub" />
  );
};

export default BrandLogo;
