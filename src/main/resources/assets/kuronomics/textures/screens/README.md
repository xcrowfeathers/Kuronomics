# Terminal backgrounds

These PNGs reproduce the original terminal background colors and geometry at
one image pixel per GUI pixel. Edit them directly, keeping their dimensions.
Buttons, text, items and hover highlights are rendered separately. The large
bank list panel is baked directly into `central_bank.png`. Marketplace inventory slot backgrounds are
baked into `market_create.png`; keep them aligned with the item slots.

| PNG | Size | Use |
| --- | --- | --- |
| `server_shop.png` | 330 × 250 | Shop catalog and six offer cards |
| `server_shop_binding.png` | 128 × 119 | Administrator binding panel |
| `server_shop_editor.png` | 300 × 250 | Shop offer editor |
| `auction_catalog.png` | 340 × 250 | Auction list tabs |
| `auction_sell.png` | 340 × 250 | Auction creation tab |
| `auction_claims.png` | 340 × 250 | Claim inbox |
| `auction_history.png` | 340 × 250 | History inbox |
| `real_estate.png` | 360 × 230 | Shared real estate background |
| `real_estate_detail.png` | 344 × 178 | Details and listing form panel |
| `real_estate_unavailable.png` | 344 × 178 | Unavailable listing form panel |
| `real_estate_inbox_row.png` | 344 × 28 | Repeated real estate inbox panel |
| `real_estate_card.png` | 344 × 30 | Repeated property card, including house icon |
| `market_catalog.png` | 360 × 226 | Marketplace catalog and My Listings; includes four row backgrounds |
| `market_create.png` | 360 × 226 | Create Listing; includes selected-item and player-inventory backgrounds |
| `market_panel.png` | 360 × 226 | Shared background for editing, buying and removing listings |
| `company_shop_selection.png` | 220 × 100 | Company selection when assigning a physical Company Shop |
| `central_bank.png` | 300 × 210 | Central Bank background, including the large panel behind category/history rows |

The bank's lighter list panel is at (14, 75), sized 272 × 102 pixels, directly
on `central_bank.png`. Edit this area to change the large rectangle behind all
category and history rows. Only the hovered category receives a code-rendered
highlight; normal rows do not paint over the texture. Text, player badges and
click positions stay separate.

Marketplace positions are measured from the PNG's top-left corner. The Create
Listing preview is at (20, 57); inventory slots start at (99, 148), with 18-pixel
spacing, and the hotbar starts at (99, 206). The price box starts at (200, 56)
and is 100 pixels wide; its label aligns with the text inset at x = 204.
Opening hours and status messages are live text centered below the PNG.

For a resource pack, use the same paths under
`assets/kuronomics/textures/screens/`. Otherwise rebuild the mod after editing
the source PNGs. Keep the artwork opaque, as in the supplied backgrounds.
