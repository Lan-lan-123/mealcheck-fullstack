export function categoryName(c) {
  return (
    {
      staple: '主食',
      protein: '蛋白质',
      vegetable: '蔬菜',
      fruit: '水果',
      dairy: '乳制品',
      soup: '汤品',
      drink: '饮品',
      dessert: '甜品',
      fried: '油炸',
      oily: '高油',
      other: '其他'
    }[c] || c
  )
}
