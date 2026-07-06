/**
 * TME 用户头像组件。
 *
 * 职责：
 * - 根据内部头像域名和员工名拼接头像地址；
 * - 在头像地址缺失时回退到默认图标，避免浏览器请求 `/undefined` 这类无效资源。
 */
import type { FC } from 'react';
import { Avatar } from 'antd';
import type { AvatarProps } from 'antd';
import avatarIcon from './assets/avatar.gif';

interface Props extends AvatarProps {
  staffName?: string;
  avatarImg?: string;
}
const { tmeAvatarUrl } = process.env;

const TMEAvatar: FC<Props> = ({ staffName, avatarImg, ...restProps }) => {
  // 开源本地环境通常没有 tmeAvatarUrl/avatarImg，不能把 undefined 字符串化为真实 src。
  const avatarSrc = tmeAvatarUrl && staffName ? `${tmeAvatarUrl}${staffName}.png` : avatarImg;

  return (
    <Avatar
      src={avatarSrc || undefined}
      alt="avatar"
      icon={<img src={avatarIcon} alt="" />}
      {...restProps}
    />
  );
};

export default TMEAvatar;
