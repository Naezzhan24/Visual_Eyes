const {chromium}=require('playwright');
(async()=>{const b=await chromium.launch({executablePath:'/opt/pw-browsers/chromium'});
const p=await b.newPage({deviceScaleFactor:4});await p.goto('file://'+__dirname+'/file_access_alts.html');
await p.locator('#all').screenshot({path:'file_access_options.png',omitBackground:true});
for(let i=0;i<4;i++) await p.locator('#a'+i+' svg').screenshot({path:`file_access_${'ABCD'[i]}.png`,omitBackground:true});
await b.close();})();
