const {chromium}=require('playwright');
(async()=>{const b=await chromium.launch({executablePath:'/opt/pw-browsers/chromium'});
const p=await b.newPage({deviceScaleFactor:4});await p.goto('file://'+__dirname+'/icons.html');
await p.addStyleTag({content:'.lbl{display:none}.wrap{width:auto;display:inline-flex;flex-wrap:nowrap;gap:32px}'});
await p.locator('#all').screenshot({path:'icons_only.png',omitBackground:true});
for(let i=0;i<5;i++) await p.locator('#i'+i+' svg').screenshot({path:`icon_${i+1}.png`,omitBackground:true});
await b.close();})();
