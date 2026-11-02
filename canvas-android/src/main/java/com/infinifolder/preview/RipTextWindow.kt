package com.infinifolder.preview

/** RIP's independent CP437/ANSI TTY window, composited directly onto the indexed screen. */
internal class RipTextWindow(private val pixels:IntArray,private val width:Int,private val scale:Int,
    private val work:(Int)->Unit,private val warn:(String)->Unit) {
    private var x0=0;private var y0=0;private var x1=79;private var y1=42;private var size=0
    private var wrap=true;private var enabled=true
    private var column=0;private var row=0;private var savedColumn=0;private var savedRow=0
    private var foreground=7;private var background=0;private var bold=false;private var inverse=false
    private var escape:StringBuilder?=null
    private val dimensions=arrayOf(8 to 8,7 to 8,8 to 14,7 to 14,16 to 14)
    private val cols get()=x1-x0+1
    private val rows get()=y1-y0+1
    private val cellWidth get()=dimensions[size].first*scale
    private val cellHeight get()=dimensions[size].second*scale
    fun reset() {
        x0=0;y0=0;x1=79;y1=42;size=0;wrap=true;enabled=true
        home();savedColumn=0;savedRow=0;foreground=7;background=0;bold=false;inverse=false;escape=null
    }
    fun configure(bounds:IntArray,newWrap:Int,newSize:Int) {
        require(newWrap in 0..1){"Invalid RIP text wrapping"}
        // Historical RIPdraw scenes use a nonzero size when zero bounds disable the TTY.
        if(bounds.all {it==0}) {enabled=false;escape=null;return}
        require(newSize in dimensions.indices){"Invalid RIP text cell size"}
        val (cw,ch)=dimensions[newSize]
        require(bounds[0]<=bounds[2] && bounds[1]<=bounds[3] && bounds[2]<640/cw && bounds[3]<350/ch){"Invalid RIP text window"}
        val same=enabled && x0==bounds[0] && y0==bounds[1] && x1==bounds[2] && y1==bounds[3] && size==newSize
        x0=bounds[0];y0=bounds[1];x1=bounds[2];y1=bounds[3];size=newSize;wrap=newWrap==1;enabled=true
        if(!same){home();escape=null}
    }
    fun home(){column=0;row=0}
    fun go(x:Int,y:Int){if(enabled){column=x.coerceIn(0,cols-1);row=y.coerceIn(0,rows-1)}}
    private fun fillCells(fromColumn:Int,fromRow:Int,toColumn:Int,toRow:Int,colour:Int) {
        if(!enabled)return
        val left=(x0+fromColumn.coerceIn(0,cols-1))*cellWidth
        val right=(x0+toColumn.coerceIn(0,cols-1)+1)*cellWidth
        val top=(y0+fromRow.coerceIn(0,rows-1))*cellHeight
        val bottom=(y0+toRow.coerceIn(0,rows-1)+1)*cellHeight
        for(y in top until bottom){pixels.fill(colour,y*width+left,y*width+right);work(right-left)}
    }
    fun clear(){if(enabled){fillCells(0,0,cols-1,rows-1,0);home()}}
    fun eraseToEnd(){if(enabled && column<cols)fillCells(column,row,cols-1,row,0)}
    private fun newLine() {
        if(row<rows-1){row++;return}
        if(!wrap)return
        val left=x0*cellWidth;val right=(x1+1)*cellWidth;val top=y0*cellHeight;val bottom=(y1+1)*cellHeight
        for(y in top until bottom-cellHeight) {
            pixels.copyInto(pixels,y*width+left,(y+cellHeight)*width+left,(y+cellHeight)*width+right)
            work(right-left)
        }
        fillCells(0,rows-1,cols-1,rows-1,background)
    }
    private fun glyph(code:Int) {
        if(column>=cols) {
            if(!wrap)return
            column=0;newLine()
        }
        val fg=if(inverse)background else foreground or (if(bold)8 else 0)
        val bg=if(inverse)foreground or (if(bold)8 else 0) else background
        val left=(x0+column)*cellWidth;val top=(y0+row)*cellHeight
        for(yy in 0 until cellHeight) {
            for(xx in 0 until cellWidth) {
                val lit=Cp437Font.isForeground(code,xx*8/cellWidth,yy*8/cellHeight*2)
                pixels[(top+yy)*width+left+xx]=if(lit)fg else bg
            }
            work(cellWidth)
        }
        column++
    }
    fun write(text:String) {
        if(!enabled)return
        for(ch in text) {
            work(1)
            val pending=escape
            if(pending!=null) {
                pending.append(ch)
                if(pending.length==1) {
                    if(ch=='[')continue
                    when(ch){'7'->{savedColumn=column;savedRow=row};'8'->go(savedColumn,savedRow)
                        else->warn("Unsupported TTY escape: ${ch.code}")}
                    escape=null;continue
                }
                if(pending.length>128){warn("TTY escape exceeds rendering limit");escape=null;continue}
                if(ch in '@'..'~'){ansi(pending.toString());escape=null}
                continue
            }
            when(ch) {
                '\u001b'->escape=StringBuilder()
                '\r'->column=0
                '\n'->newLine()
                '\b'->column=(column-1).coerceAtLeast(0)
                '\t'->column=((column/8+1)*8).coerceAtMost(cols)
                '\u0007'->warn("TTY bell audio is not supported")
                else->if(ch.code>=32 && ch.code!=127)glyph(ch.code)
            }
        }
    }
    private fun ansi(sequence:String) {
        val body=sequence.substring(1,sequence.length-1)
        if(body.any {it !in '0'..'9' && it!=';'}){warn("Unsupported TTY ANSI: ${sequence.last()}");return}
        val p=body.split(';').map {it.toIntOrNull() ?: 0}
        fun number(i:Int=0,default:Int=1)=p.getOrElse(i){0}.takeIf {it!=0} ?: default
        when(sequence.last()) {
            'A'->go(column,row-number());'B'->go(column,row+number())
            'C'->go(column+number(),row);'D'->go(column-number(),row)
            'E'->go(0,row+number());'F'->go(0,row-number())
            'G'->go(number()-1,row);'d'->go(column,number()-1)
            'H','f'->go(number(1)-1,number()-1)
            's'->{savedColumn=column;savedRow=row};'u'->go(savedColumn,savedRow)
            'K'->when(p[0]){0->if(column<cols)fillCells(column,row,cols-1,row,background)
                1->fillCells(0,row,column,row,background);2->fillCells(0,row,cols-1,row,background)
                else->warn("Unsupported TTY erase-line mode")}
            'J'->when(p[0]) {
                0->{if(column<cols)fillCells(column,row,cols-1,row,background);if(row<rows-1)fillCells(0,row+1,cols-1,rows-1,background)}
                1->{if(row>0)fillCells(0,0,cols-1,row-1,background);fillCells(0,row,column,row,background)}
                2->fillCells(0,0,cols-1,rows-1,background)
                else->warn("Unsupported TTY erase-display mode")
            }
            'm'->{val ansiToEga=intArrayOf(0,4,2,6,1,5,3,7)
                for(value in p)when(value) {
                    0->{foreground=7;background=0;bold=false;inverse=false}
                    1->bold=true;22->bold=false;7->inverse=true;27->inverse=false
                    39->foreground=7;49->background=0
                    in 30..37->foreground=ansiToEga[value-30]
                    in 40..47->background=ansiToEga[value-40]
                    in 90..97->foreground=ansiToEga[value-90]+8
                    in 100..107->background=ansiToEga[value-100]+8
                    else->warn("Unsupported TTY ANSI text attribute: $value")
                }
            }
            else->warn("Unsupported TTY ANSI: ${sequence.last()}")
        }
    }
}
