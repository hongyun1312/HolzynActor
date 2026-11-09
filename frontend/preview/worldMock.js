/*
 * 预览用假数据：一个 25 人的河边小镇（5 个圈子 + 1 个待补充角色）。
 *
 * 只要形状对（nodes[npc/ghost] + relations[fromKey/toKey/relationType/affinity/trust]），
 * 组件内部的圈子判定、关系强度、新鲜度、LOD 都会按真实逻辑跑——预览才有意义。
 */

/** 圈子（世界内核归属：组织 / 家族 / 职业） */
const GROUPS = [
  { id: 'shen', name: '沈家', type: 'family' },
  { id: 'shang', name: '临河商会', type: 'guild' },
  { id: 'wu', name: '镇武堂', type: 'guild' },
  { id: 'miao', name: '河神庙', type: 'religion' },
  { id: 'ya', name: '镇衙', type: 'official' }
]

/** 角色：[姓名, 圈子, 重要度, 职业, 年龄, 是否主角] */
const PEOPLE = [
  ['沈砚青', 'shen', 5, '族长', 61, 1],
  ['沈昭', 'shen', 4, '长子·账房', 34, 0],
  ['沈知微', 'shen', 4, '长女·医女', 29, 0],
  ['沈阿蘅', 'shen', 3, '次女', 22, 0],
  ['沈砚舟', 'shen', 3, '族中管事', 47, 0],
  ['沈小满', 'shen', 2, '族中幼女', 13, 0],

  ['柳三娘', 'shang', 5, '商会会首', 44, 0],
  ['周允', 'shang', 4, '商会账房', 38, 0],
  ['吴有德', 'shang', 3, '粮行东家', 52, 0],
  ['冯九', 'shang', 3, '船帮把头', 41, 0],
  ['何顺', 'shang', 2, '脚夫', 27, 0],
  ['秦小乙', 'shang', 2, '货栈伙计', 19, 0],
  ['苏娘', 'shang', 3, '茶棚掌柜', 36, 0],

  ['霍铁山', 'wu', 5, '武堂馆主', 49, 0],
  ['霍青', 'wu', 4, '武堂大师兄', 26, 0],
  ['孟十三', 'wu', 3, '武堂教习', 33, 0],
  ['白露', 'wu', 3, '武堂弟子', 21, 0],
  ['石头', 'wu', 2, '武堂杂役', 17, 0],

  ['明慧师太', 'miao', 4, '庙祝', 58, 0],
  ['净尘', 'miao', 3, '小尼', 20, 0],
  ['香客陈氏', 'miao', 2, '庙中香客', 45, 0],
  ['老庙工', 'miao', 2, '庙工', 63, 0],

  ['程主簿', 'ya', 4, '镇衙主簿', 51, 0],
  ['差役赵五', 'ya', 3, '衙役', 30, 0],
  ['更夫刘二', 'ya', 2, '更夫', 55, 0],

  ['货郎孙', null, 1, '外乡货郎', 39, 0]
]

/** 关系：[甲, 乙, 类型, 好感, 信任, 状态, 几天前] */
const RELS = [
  ['沈砚青', '沈昭', '父子', 78, 82, '亲密', 1],
  ['沈砚青', '沈知微', '父女', 82, 80, '亲密', 1],
  ['沈砚青', '沈阿蘅', '父女', 64, 60, '亲近', 2],
  ['沈砚青', '沈砚舟', '族亲', 40, 58, '正常', 4],
  ['沈砚青', '沈小满', '祖孙', 88, 70, '亲密', 1],
  ['沈昭', '沈知微', '兄妹', 62, 74, '亲近', 2],
  ['沈昭', '沈阿蘅', '兄妹', 55, 60, '亲近', 3],
  ['沈知微', '沈小满', '姑侄', 70, 66, '亲近', 2],
  ['沈砚舟', '沈小满', '叔侄', 45, 50, '正常', 5],

  ['柳三娘', '周允', '东家与账房', 48, 76, '信赖', 1],
  ['柳三娘', '吴有德', '生意伙伴', 30, 55, '正常', 3],
  ['柳三娘', '冯九', '雇主', 22, 40, '紧张', 2],
  ['柳三娘', '苏娘', '旧识', 60, 62, '亲近', 6],
  ['周允', '吴有德', '同行', 10, 34, '戒备', 8],
  ['冯九', '何顺', '把头与脚夫', 18, 44, '正常', 1],
  ['冯九', '秦小乙', '把头与伙计', -12, 30, '紧张', 2],
  ['何顺', '秦小乙', '同乡', 52, 58, '亲近', 1],
  ['吴有德', '苏娘', '赊账', 8, 38, '正常', 7],

  ['霍铁山', '霍青', '师徒', 66, 84, '亲密', 1],
  ['霍铁山', '孟十三', '同门', 44, 66, '亲近', 3],
  ['霍铁山', '白露', '师徒', 58, 72, '亲近', 2],
  ['霍青', '白露', '同门', 36, 48, '暧昧', 1],
  ['霍青', '石头', '照拂', 40, 52, '正常', 2],
  ['孟十三', '石头', '操练', 12, 30, '正常', 4],

  ['明慧师太', '净尘', '师徒', 62, 80, '亲近', 1],
  ['明慧师太', '沈知微', '医缘', 46, 64, '正常', 5],
  ['净尘', '香客陈氏', '化缘', 30, 40, '正常', 6],
  ['老庙工', '明慧师太', '庙中老仆', 55, 70, '亲近', 2],
  ['净尘', '霍青', '祈福', 24, 36, '正常', 9],

  ['程主簿', '差役赵五', '上官下属', 30, 58, '正常', 1],
  ['程主簿', '沈砚青', '乡绅往来', 34, 60, '正常', 4],
  ['差役赵五', '更夫刘二', '同僚', 42, 50, '正常', 2],
  ['差役赵五', '冯九', '巡查', -20, 24, '敌对', 3],
  ['更夫刘二', '苏娘', '邻居', 38, 46, '正常', 1],

  ['货郎孙', '秦小乙', '买卖', 16, 26, '正常', 12],
  ['货郎孙', '沈阿蘅', '送货', 28, 30, '正常', 10]
]

/** 关系状态 → 中文（后端本来就会给 relationStateLabel，这里对齐） */
const STATE_LABEL = {
  亲密: '亲密', 亲近: '亲近', 正常: '正常', 信赖: '信赖', 紧张: '紧张', 戒备: '戒备', 敌对: '敌对', 暧昧: '暧昧'
}

/**
 * 造一份与后端 `GET /api/worlds/{id}/character-relations` 同形的数据。
 *
 * @returns {{nodes: Array, relations: Array}} 关系图原始数据
 */
export function buildMockWorld() {
  const idOf = new Map()
  const nodes = PEOPLE.map(([name, groupId, importance, occupation, age, protagonist], i) => {
    const id = groupId ? `npc-${i + 1}` : 'ghost-1'
    idOf.set(name, id)
    const group = GROUPS.find(g => g.id === groupId)
    return {
      id,
      name,
      kind: groupId ? 'npc' : 'ghost',
      importance,
      isProtagonist: protagonist,
      title: occupation,
      meta: group
        ? {
            groupId: group.id,
            groupName: group.name,
            groupType: group.type,
            occupation,
            age,
            gender: /娘|女|师太|净尘|白露|阿蘅|知微|小满|陈氏|苏/.test(name) ? '女' : '男'
          }
        : { occupation }
    }
  })

  const relations = RELS.map(([from, to, type, affinity, trust, state, daysAgo], i) => {
    const when = new Date(Date.now() - daysAgo * 86400000).toISOString()
    return {
      id: i + 1,
      fromKey: idOf.get(from),
      toKey: idOf.get(to),
      fromName: from,
      toName: to,
      relationType: type,
      description: '',
      affinity,
      trust,
      relationState: state,
      relationStateLabel: STATE_LABEL[state] || state,
      lastInteractionAt: when
    }
  })

  // 双向合并的样本：把「父子」「师徒」两类里各补一条反向记录（真实数据里很常见）
  const mirror = (index, type) => {
    const r = relations[index]
    relations.push({ ...r, id: relations.length + 1, fromKey: r.toKey, toKey: r.fromKey, fromName: r.toName, toName: r.fromName, relationType: type })
  }
  mirror(0, '长辈')
  mirror(18, '晚辈')

  return { nodes, relations }
}
