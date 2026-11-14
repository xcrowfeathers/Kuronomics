// Example kubejs script
// Requires KubeJS and Rhino dependency
// This file only registers handlers, it never pays money while scripts reload.

KuronomyEvents.companyPayday(event => {
  // logs company balance of a specified company after company payday
  const company = Kuronomy.companyByName('Kuro Industries')
  if (company == null) return
  const statement = event.data.statements().get(company.id())
  if (statement == null) return
  const account = Kuronomy.companyBalance(String(company.id()))
  console.info(company.name() + ': Company PayDay complete, posted cents = ' + account.get('postedCents'))
})

KuronomyEvents.playerPayday(event => {
  // Same thing as above but with players.
  const account = Kuronomy.playerBalance(String(event.data.player()))
  console.info('Player PayDay complete, posted cents = ' + account.get('postedCents'))
})
