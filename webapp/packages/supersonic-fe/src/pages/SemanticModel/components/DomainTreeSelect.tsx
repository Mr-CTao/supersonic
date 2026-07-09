import { message, TreeSelect } from 'antd';
import type { TreeDataNode } from 'antd';
import { useEffect, useState, useRef } from 'react';
import type { FC } from 'react';
import { getDomainList } from '../../SemanticModel/service';
import { constructorClassTreeFromList, addPathInTreeData } from '../utils';
import styles from './style.less';

type Props = {
  value?: any;
  width?: number | string;
  firstLevelOnly?: boolean;
  onChange?: () => void;
  treeSelectProps?: Record<string, any>;
  onDefaultValue?: (value?: number) => void;
};

const DomainTreeSelect: FC<Props> = ({
  value,
  width = 250,
  firstLevelOnly = false,
  onChange,
  onDefaultValue,
  treeSelectProps = {},
}) => {
  const [domainTree, setDomainTree] = useState<TreeDataNode[]>([]);

  const defaultValue = useRef<number>();
  const [initState, setInitState] = useState<boolean>(false);

  const initProjectTree = async () => {
    const { code, data, msg } = await getDomainList();
    if (!initState) {
      setInitState(true);
    }
    const domainList = data;
    if (code === 200) {
      const treeData = addPathInTreeData(constructorClassTreeFromList(domainList));
      if (firstLevelOnly) {
        const firstLevelNodes = treeData.map((item: any) => {
          return { ...item, children: [] };
        });
        defaultValue.current = firstLevelNodes[0]?.id;
        onDefaultValue?.(defaultValue.current);
        setDomainTree(firstLevelNodes);
        return;
      }
      setDomainTree(treeData);
    } else {
      message.error(msg);
    }
  };

  useEffect(() => {
    initProjectTree();
  }, []);

  return (
    <div
      style={{
        width,
      }}
    >
      {defaultValue.current ? (
        <TreeSelect
          defaultValue={defaultValue.current}
          classNames={{ popup: { root: styles.domainSelector } }}
          showSearch
          key="defaultValue"
          style={{ width: '100%' }}
          value={value}
          styles={{ popup: { root: { maxHeight: 400, overflow: 'auto' } } }}
          placeholder={'请选择主题域'}
          allowClear
          multiple
          treeNodeFilterProp="title"
          treeDefaultExpandAll
          onChange={onChange}
          treeData={domainTree}
          {...treeSelectProps}
        />
      ) : (
        <TreeSelect
          defaultValue={defaultValue.current}
          classNames={{ popup: { root: styles.domainSelector } }}
          showSearch
          key="preRender"
          style={{ width: '100%' }}
          value={value}
          styles={{ popup: { root: { maxHeight: 400, overflow: 'auto' } } }}
          placeholder={'请选择主题域'}
          allowClear
          multiple
          treeNodeFilterProp="title"
          treeDefaultExpandAll
          onChange={onChange}
          treeData={domainTree}
          {...treeSelectProps}
        />
      )}
    </div>
  );
};

export default DomainTreeSelect;
