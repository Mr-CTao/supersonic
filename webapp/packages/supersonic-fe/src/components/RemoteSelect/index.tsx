import React, {
  useState,
  useRef,
  useMemo,
  useEffect,
  forwardRef,
  useImperativeHandle,
  Ref,
} from 'react';
import { Select, Spin, Empty } from 'antd';
import debounce from 'lodash/debounce';
// import type { ValueTextType } from '@/constants';
import isFunction from 'lodash/isFunction';

type Props = {
  fetchOptions: (...restParams: any[]) => Promise<{ label: any; value: any }[]>;
  debounceTimeout?: number;
  formatPropsValue?: (value: any) => any;
  formatFetchOptionsParams?: (inputValue: string, ctx?: any) => any[];
  formatOptions?: (data: any, ctx: any) => any[];
  autoInit?: boolean;
  disabledSearch?: boolean;
  [key: string]: any;
};
type SelectOptions = {
  label: string;
} & {
  text: string;
} & {
  value: any;
};

export type RemoteSelectImperativeHandle = {
  emitSearch: (value: string) => void;
};

const { Option } = Select;

/**
 * 将多选 Select 的受控值归一化为数组。
 *
 * @param value 外部表单或接口传入的原始值，历史数据可能是逗号分隔字符串。
 * @returns antd `mode="multiple"` 期望的数组值。
 * @throws 不主动抛出异常；无法识别的空值统一回退为空数组，避免 antd 6 开发态 warning。
 */
const normalizeMultipleSelectValue = (value: any) => {
  if (Array.isArray(value)) {
    return value;
  }
  if (typeof value === 'string') {
    return value
      .split(',')
      .map((item) => item.trim())
      .filter(Boolean);
  }
  if (value === undefined || value === null) {
    return [];
  }

  return [value];
};

const DebounceSelect = forwardRef(
  (
    {
      autoInit = false,
      fetchOptions,
      debounceTimeout = 500,
      formatPropsValue,
      formatFetchOptionsParams,
      formatOptions,
      disabledSearch = false,
      ...restProps
    }: Props,
    ref: Ref<any>,
  ) => {
    const props = { ...restProps };
    const { ctx, filterOption } = props;
    if (isFunction(formatPropsValue)) {
      props.value = formatPropsValue(props.value);
    }
    props.value = normalizeMultipleSelectValue(props.value);
    const [fetching, setFetching] = useState(false);
    const [options, setOptions] = useState(props.options || props.source || []);

    useImperativeHandle(ref, () => ({
      emitSearch: (value: string) => {
        loadOptions(value, true);
      },
    }));

    useEffect(() => {
      if (autoInit) {
        loadOptions('', true);
      }
    }, []);
    useEffect(() => {
      setOptions(props.source || []);
    }, [props.source]);

    const fetchRef = useRef(0);

    const loadOptions = (value: string, allowEmptyValue?: boolean) => {
      setOptions([]);
      if (disabledSearch) {
        return;
      }
      if (!allowEmptyValue && !value) return;
      fetchRef.current += 1;
      const fetchId = fetchRef.current;
      setFetching(true);
      const fetchParams = formatFetchOptionsParams ? formatFetchOptionsParams(value, ctx) : [value];
      // eslint-disable-next-line prefer-spread
      fetchOptions.apply(null, fetchParams).then((newOptions) => {
        if (fetchId !== fetchRef.current || !Array.isArray(newOptions)) {
          return;
        }
        let finalOptions = newOptions;
        if (formatOptions && isFunction(formatOptions)) {
          finalOptions = formatOptions(newOptions, ctx);
        }
        finalOptions =
          filterOption && Array.isArray(finalOptions)
            ? filterOption?.(finalOptions, ctx)
            : finalOptions;
        setOptions(finalOptions);
        setFetching(false);
      });
    };

    const debounceFetcher = useMemo(() => {
      return debounce(loadOptions, debounceTimeout, {
        trailing: true,
      });
      // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [fetchOptions, debounceTimeout]);

    return (
      <Select
        style={{ minWidth: '100px' }}
        showSearch
        allowClear
        mode="multiple"
        // onClear={() => {
        //   if (autoInit) {
        //     loadOptions('', true);
        //   } else {
        //     setOptions([]);
        //   }
        // }}
        onSearch={debounceFetcher}
        {...props}
        filterOption={false} // 保持对props中filterOption属性的复写，不可变更位置
        notFoundContent={
          fetching ? <Spin size="small" /> : <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} />
        }
        loading={fetching}
      >
        {options.map((option: SelectOptions) => (
          <Option value={option.value} key={option.value}>
            {option.text || option.label}
          </Option>
        ))}
      </Select>
    );
  },
);
export default DebounceSelect;
