const {chromium}=require('playwright');
(async()=>{const b=await chromium.launch({executablePath:'/opt/pw-browsers/chromium'});
const p=await b.newPage({deviceScaleFactor:3});await p.goto('file://'+__dirname+'/icons.html');
await p.locator('#all').screenshot({path:'accessibility_features.png',omitBackground:true});
for(let i=0;i<5;i++) await p.locator('#i'+i).screenshot({path:`feature_${i+1}.png`,omitBackground:true});
await b.close();})();
