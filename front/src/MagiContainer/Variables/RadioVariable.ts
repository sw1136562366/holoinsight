

  import { Radio } from 'antd';
  const Group = Radio.Group
  
  type VariablePlugin = any;
  

  export default class SelectVariable implements VariablePlugin {
    config!: any;
    context!: any;
    isArray?: boolean;
  
    init(config: any) {
      this.config = config;
      if (this.config.options && this.config.options.multiple)
        this.isArray = true;
    }
  
    viewProps?: any;
    /**
     * 获取变量的值
     * @param current - 原始值
     */
    getValue(current?: string | string[]): any {
      const { values = '', multiple } = this.config.options || {};
      const list: string[] = values.split(',');
  
      if (!multiple) {
        if (current && list.includes(current as string)) return current;
        return undefined;
      }
  
      if (current) {
        return (Array.isArray(current) ? current : [current]).filter(
          (item: string) => list.includes(item),
        );
      }
  
      return [];
    }
  
    getAllValues() {
      const { values = '' } = this.config.options || {};
      return values.split(',');
    }
  
    async resolve() {
      const {
        width = 'auto',
        placeholder,
        supportAll,
        multiple,
      } = this.config.options || {};
      const list = this.getAllValues();
      const option = {
        options: (supportAll ? [{ label: 'ALL', value: '__ALL__' }] : []).concat(
          list.map((item: string) => ({ label: item, value: item })),
        ),
        optionType: "button",
        style: { width, minWidth: 100 },
      };
      this.viewProps = option;
    }
  
    view = Group;
  }
  