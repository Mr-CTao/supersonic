import { useState, useEffect } from 'react';
import type { FC } from 'react';
import { Select } from 'antd';
import type { UserItem } from './service';
import { useModel } from '@umijs/max';
import styles from './index.less';
import TMEAvatar from '../TMEAvatar';

interface Props {
  value?: string | string[];
  placeholder?: string;
  isMultiple?: boolean;
  onChange?: (owners: string | string[]) => void;
}

/**
 * 归一化人员选择器的受控值。
 *
 * @param value 表单字段原始值，历史接口可能返回单个用户名字符串。
 * @param isMultiple 是否启用多选模式。
 * @returns 与当前 Select 模式匹配的值类型。
 * @throws 不主动抛出异常；空值按 antd 受控组件约定返回 `undefined` 或空数组。
 */
const normalizePersonValue = (value: string | string[] | undefined, isMultiple: boolean) => {
  if (isMultiple) {
    if (Array.isArray(value)) {
      return value;
    }
    if (typeof value === 'string' && value.trim()) {
      return value
        .split(',')
        .map((item) => item.trim())
        .filter(Boolean);
    }
    return [];
  }

  return Array.isArray(value) ? value[0] : value;
};

const SelectTMEPerson: FC<Props> = ({ placeholder, value, isMultiple = true, onChange }) => {
  const [userList, setUserList] = useState<UserItem[]>([]);
  const allUserModel = useModel('allUserData');
  const { allUserList, MrefreshUserList } = allUserModel;

  const queryTmePersonData = async () => {
    const list = await MrefreshUserList();
    setUserList(list);
  };
  useEffect(() => {
    if (Array.isArray(allUserList) && allUserList.length > 0) {
      setUserList(allUserList);
    } else {
      queryTmePersonData();
    }
  }, []);

  return (
    <Select
      value={normalizePersonValue(value, isMultiple)}
      placeholder={placeholder ?? '请选择用户名'}
      mode={isMultiple ? 'multiple' : undefined}
      allowClear
      showSearch
      className={styles.selectPerson}
      onChange={onChange}
    >
      {userList.map((item) => {
        return (
          <Select.Option key={item.name} value={item.name}>
            <TMEAvatar size="small" staffName={item.name} />
            <span className={styles.userText}>{item.displayName}</span>
          </Select.Option>
        );
      })}
    </Select>
  );
};

export default SelectTMEPerson;
